package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.unavailable;
import static cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.databaseIdentitySha;
import static cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.outside;
import static cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.failure;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Sequential exact Spring transactions owned by one still-held registration identity lease. */
final class ProductPromptRegistrationTransactions {
    private ProductPromptRegistrationTransactions() { }

    static <T>T execute(ProductPromptRegistrationLease.Lease lease,HikariDataSource source,
            DataSourceTransactionManager manager,Instant cutoff,Supplier<T> operation) {
        Objects.requireNonNull(cutoff);Objects.requireNonNull(operation);outside();
        if(lease.phase!=null || manager.getDataSource()!=source)throw unavailable();
        lease.requireCurrent();lease.deadline.beforeBorrow(source,cutoff);
        var transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(lease.deadline.transactionSeconds(cutoff));
        Phase[] captured=new Phase[1];
        try {
            T result=transaction.execute(status->{
                Object resource=TransactionSynchronizationManager.getResource(source);
                if(!(resource instanceof ConnectionHolder holder))throw unavailable();
                var phase=new Phase(source,holder,cutoff);captured[0]=phase;lease.phase=phase;
                // Restore failures in afterCompletion must be carried out: Spring logs and
                // suppresses callback exceptions, so throwing there alone cannot refuse success.
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void beforeCommit(boolean readOnly) {
                        lease.requireCurrent();phase.requireCurrent(lease);phase.commitAdmitted=true;
                    }
                    @Override public void beforeCompletion() {
                        try {
                            int timeout=ProductPromptRegistrationDeadline.CLEANUP_MILLIS;
                            if(phase.commitAdmitted) {
                                try{lease.requireCurrent();timeout=lease.deadline.networkMillis(cutoff);}
                                catch(RuntimeException expired){phase.fail(lease,expired);return;}
                            }
                            phase.connection.setNetworkTimeout(lease.owner.network,timeout);
                        }catch(SQLException|RuntimeException failure){phase.fail(lease,failure);}
                    }
                    @Override public void afterCompletion(int completion) {
                        phase.completion=completion;
                        if(completion==STATUS_UNKNOWN){phase.fail(lease,unavailable());return;}
                        try{phase.settings.restoreNetwork(lease.owner.network);}
                        catch(SQLException|RuntimeException failure){phase.fail(lease,failure);}
                    }
                });
                phase.requireCurrent(lease);lease.requireCurrent();
                T value=operation.get();
                lease.requireCurrent();phase.requireCurrent(lease);return value;
            });
            lease.phase=null;
            if(captured[0]==null)throw unavailable();
            if(captured[0].failure!=null)throw failure("Registration transaction cleanup failed",captured[0].failure);
            if(captured[0].completion!=TransactionSynchronization.STATUS_COMMITTED)throw unavailable();
            outside();lease.requireCurrent();lease.deadline.remaining(cutoff);return result;
        }catch(DataAccessException|TransactionException failed){
            lease.invalidate();
            if(captured[0]!=null && captured[0].failure!=null && captured[0].failure!=failed)
                failed.addSuppressed(captured[0].failure);
            throw failure("Registration transaction result unavailable",failed);
        }
        catch(RuntimeException|Error failed){lease.invalidate();throw failed;}
        finally{lease.phase=null;}
    }

    static final class Phase {
        final HikariDataSource source;
        final ConnectionHolder holder;
        final Connection connection;
        final Instant cutoff;
        final Settings settings;
        boolean commitAdmitted;
        Throwable failure;
        int completion=-1;

        Phase(HikariDataSource source,ConnectionHolder holder,Instant cutoff) {
            this.source=source;this.holder=holder;this.connection=holder.getConnection();this.cutoff=cutoff;
            try{settings=new Settings(connection,false);}catch(SQLException failure){throw failure("Registration transaction settings unavailable",failure);}
        }
        void requireCurrent(ProductPromptRegistrationLease.Lease lease) {
            if(failure!=null || lease.phase!=this || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.getResourceMap().size()!=1
                    || TransactionSynchronizationManager.getResource(source)!=holder
                    || !holder.isSynchronizedWithTransaction() || holder.getConnection()!=connection)throw unavailable();
            try {
                connection.setNetworkTimeout(lease.owner.network,lease.deadline.networkMillis(cutoff));
                if(connection==lease.maintenance || connection.isClosed() || connection.getAutoCommit()
                        || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED
                        || !lease.identity(source).equals(databaseIdentitySha(connection)))throw unavailable();
            }catch(SQLException failure){throw failure("Registration transaction observation unavailable",failure);}
        }
        void fail(ProductPromptRegistrationLease.Lease lease,Throwable reason) {
            if(failure!=null){failure.addSuppressed(reason);return;}
            failure=reason;lease.owner.poison(source,connection,reason);
        }
    }

    /** Raw M/probe restoration; worker transaction state remains Spring's responsibility. */
    static final class Settings {
        private final Connection connection;
        private final boolean autoCommit,readOnly;
        private final int isolation,networkTimeout;
        Settings(Connection connection)throws SQLException {this(connection,true);}
        Settings(Connection connection,boolean requireIdle)throws SQLException {
            this.connection=connection;autoCommit=connection.getAutoCommit();readOnly=connection.isReadOnly();
            isolation=connection.getTransactionIsolation();networkTimeout=connection.getNetworkTimeout();
            if(connection.isClosed() || networkTimeout<0 || requireIdle&&!autoCommit)throw unavailable();
        }
        void network(Executor executor,int millis)throws SQLException {connection.setNetworkTimeout(executor,millis);}
        void restoreNetwork(Executor executor)throws SQLException {connection.setNetworkTimeout(executor,networkTimeout);}
        void restore(Executor executor)throws SQLException {
            connection.setTransactionIsolation(isolation);connection.setReadOnly(readOnly);
            // Caller has already confirmed rollback. Restore autocommit before removing the
            // bounded network setting so its JDBC operation also has a finite network budget.
            connection.setAutoCommit(autoCommit);restoreNetwork(executor);
        }
    }
}
