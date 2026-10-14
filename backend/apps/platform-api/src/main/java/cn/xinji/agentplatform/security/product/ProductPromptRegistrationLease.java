package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.unavailable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Prompt-only mTLS identity lock for exact REGISTER and BODY_CLEAR approvals. */
final class ProductPromptRegistrationLease implements AutoCloseable {
    private record Maintainer(String subject,String organization,String certificateSha,
                              String roles,String status,long version) { }
    final HikariDataSource mainSource,authoritySource;
    final DataSourceTransactionManager mainManager,authorityManager;
    final Clock clock;
    final ThreadPoolExecutor network=new ThreadPoolExecutor(0,1,1,TimeUnit.SECONDS,
            new SynchronousQueue<>(),work->{var thread=new Thread(work,"prompt-registration-network");
                thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore admission=new Semaphore(1);
    private volatile boolean closed;

    ProductPromptRegistrationLease(HikariDataSource mainSource,HikariDataSource authoritySource,
            DataSourceTransactionManager mainManager,DataSourceTransactionManager authorityManager,Clock clock) {
        this.mainSource=Objects.requireNonNull(mainSource);this.authoritySource=Objects.requireNonNull(authoritySource);
        this.mainManager=Objects.requireNonNull(mainManager);this.authorityManager=Objects.requireNonNull(authorityManager);
        this.clock=Objects.requireNonNull(clock);
        try{requirePools();}catch(RuntimeException|Error failed){network.shutdownNow();throw failed;}
    }
    <T>T withLease(HttpServletRequest request,ProductPromptRegisterApproval.Witness approval,Function<Lease,T> operation) {
        return withLease(request,Objects.requireNonNull(approval),null,operation);
    }
    <T>T withLease(HttpServletRequest request,ProductPromptBodyClearApproval.Witness approval,Function<Lease,T> operation) {
        return withLease(request,null,Objects.requireNonNull(approval),operation);
    }
    private <T>T withLease(HttpServletRequest request,ProductPromptRegisterApproval.Witness approval,
            ProductPromptBodyClearApproval.Witness bodyClearApproval,Function<Lease,T> operation) {
        outside();Objects.requireNonNull(operation);requirePools();
        if((approval==null)==(bodyClearApproval==null))throw unavailable();
        if(!admission.tryAcquire())throw unavailable();
        Connection connection=null;ProductPromptRegistrationTransactions.Settings settings=null;
        Lease lease=null;Throwable failure=null;
        try {
            requireApproval(approval,bodyClearApproval);X509Certificate certificate=certificate(request);
            Instant approvedUntil=approval!=null?approval.expiresAt():bodyClearApproval.expiresAt();
            Instant expiry=min(approvedUntil,certificate.getNotAfter().toInstant());
            var deadline=new ProductPromptRegistrationDeadline(clock,expiry);
            String primarySha=probeMain(deadline);
            requireApproval(approval,bodyClearApproval);deadline.beforeBorrow(authoritySource,expiry);
            connection=authoritySource.getConnection();
            settings=new ProductPromptRegistrationTransactions.Settings(connection);
            settings.network(network,deadline.networkMillis(expiry));
            connection.setReadOnly(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            String authoritySha=databaseIdentitySha(connection);
            if(primarySha.equals(authoritySha))throw unavailable();
            Maintainer maintainer=read(connection,deadline,expiry,true);
            lease=new Lease(this,approval,bodyClearApproval,certificate,connection,deadline,maintainer,primarySha,authoritySha);
            lease.requireCurrent();T result=operation.apply(lease);lease.requireCurrent();outside();return result;
        }catch(SQLException unavailable){failure=unavailable;throw failure("Registration connection unavailable",unavailable);}
        catch(RuntimeException|Error thrown){failure=thrown;throw thrown;}
        finally {
            if(lease!=null)lease.active=false;
            RuntimeException cleanup=release(authoritySource,connection,settings);
            admission.release();
            if(cleanup!=null){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    private static void requireApproval(ProductPromptRegisterApproval.Witness approval,
            ProductPromptBodyClearApproval.Witness bodyClearApproval) {
        if(approval!=null)approval.requireCurrent();else bodyClearApproval.requireCurrent();
    }
    private String probeMain(ProductPromptRegistrationDeadline deadline)throws SQLException {
        deadline.beforeBorrow(mainSource,deadline.until());
        Connection connection=null;ProductPromptRegistrationTransactions.Settings settings=null;
        Throwable failure=null;
        try {
            connection=mainSource.getConnection();settings=new ProductPromptRegistrationTransactions.Settings(connection);
            settings.network(network,deadline.networkMillis(deadline.until()));
            return databaseIdentitySha(connection);
        }catch(SQLException|RuntimeException|Error thrown){failure=thrown;throw thrown;}
        finally {
            RuntimeException cleanup=release(mainSource,connection,settings);
            if(cleanup!=null){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    private RuntimeException release(HikariDataSource pool,Connection connection,
            ProductPromptRegistrationTransactions.Settings settings) {
        if(connection==null)return null;
        try {
            if(settings==null)throw unavailable();
            settings.network(network,ProductPromptRegistrationDeadline.CLEANUP_MILLIS);
            if(!connection.getAutoCommit())connection.rollback();
            settings.restore(network);connection.close();return null;
        }catch(SQLException|RuntimeException failed){poison(pool,connection,failed);return failure("Registration connection release unknown",failed);}
    }
    void poison(HikariDataSource pool,Connection connection,Throwable failure) {
        closed=true;
        try{pool.evictConnection(connection);}catch(RuntimeException eviction){failure.addSuppressed(eviction);}
        // Invalidate this proxy synchronously as well: pool eviction may close the physical
        // connection asynchronously and alone cannot prevent a following commit call.
        try{connection.close();}catch(SQLException|RuntimeException close){failure.addSuppressed(close);}
    }
    private void requirePools() {
        if(closed || mainSource==authoritySource || mainManager.getDataSource()!=mainSource
                || authorityManager.getDataSource()!=authoritySource)throw unavailable();
        ProductPromptRegistrationDeadline.validate(mainSource,1);
        ProductPromptRegistrationDeadline.validate(authoritySource,2);
    }
    private X509Certificate certificate(HttpServletRequest request) {
        if(request==null || !request.isSecure())throw denied();
        Object value=request.getAttribute("jakarta.servlet.request.X509Certificate");
        if(!(value instanceof X509Certificate[] chain) || chain.length==0 || chain[0]==null)throw denied();
        validCertificate(chain[0]);return chain[0];
    }
    private void validCertificate(X509Certificate certificate) {
        try{
            certificate.checkValidity(Date.from(clock.instant()));
            if(!clock.instant().isBefore(certificate.getNotAfter().toInstant()))throw denied();
        }catch(java.security.cert.CertificateException failed){throw denied();}
    }
    private Maintainer read(Connection connection,ProductPromptRegistrationDeadline deadline,Instant cutoff,boolean lock) {
        try {
            connection.setNetworkTimeout(network,deadline.networkMillis(cutoff));
            String sql="SELECT subject_ref,organization,certificate_sha,allowed_roles,status,version "
                    +"FROM id2_maintainer WHERE singleton_key=1"+(lock?" FOR UPDATE NOWAIT":"");
            try(var statement=connection.prepareStatement(sql)) {
                statement.setQueryTimeout(deadline.querySeconds(cutoff));
                try(var rows=statement.executeQuery()) {
                    if(!rows.next())throw denied();
                    var result=new Maintainer(rows.getString(1),rows.getString(2),rows.getString(3),
                            rows.getString(4),rows.getString(5),rows.getLong(6));
                    if(rows.next())throw unavailable();return result;
                }
            }
        }catch(SQLException failed){throw failure("Registration maintainer observation unavailable",failed);}
    }
    static String databaseIdentitySha(Connection connection) {
        try {
            var values=Map.of("format","PROMPT_REGISTER_DATABASE/v1","url",connection.getMetaData().getURL(),
                    "schema",connection.getSchema(),"user",connection.getMetaData().getUserName());
            return new ProductComparisonJson(new ObjectMapper()).sha(values);
        }catch(SQLException|NullPointerException failed){throw failure("Registration database identity unavailable",failed);}
    }
    static void outside() {
        if(TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())throw unavailable();
    }
    private static ProductPromptAccessDenied denied(){return new ProductPromptAccessDenied();}
    static IllegalStateException failure(String stage,Throwable cause){return new IllegalStateException(stage,cause);}
    private static Instant min(Instant first,Instant second){return first.isBefore(second)?first:second;}
    @Override public void close(){closed=true;network.shutdown();}

    static final class Lease {
        final ProductPromptRegistrationLease owner;
        final ProductPromptRegistrationDeadline deadline;
        final Connection maintenance;
        private final ProductPromptRegisterApproval.Witness approval;
        private final ProductPromptBodyClearApproval.Witness bodyClearApproval;
        private final X509Certificate certificate;
        private final Maintainer expected;
        private final String mainSha,authoritySha;
        private final Thread thread=Thread.currentThread();
        private boolean active=true;
        ProductPromptRegistrationTransactions.Phase phase;

        private Lease(ProductPromptRegistrationLease owner,ProductPromptRegisterApproval.Witness approval,
                ProductPromptBodyClearApproval.Witness bodyClearApproval,
                X509Certificate certificate,Connection maintenance,ProductPromptRegistrationDeadline deadline,
                Maintainer expected,String mainSha,String authoritySha) {
            this.owner=owner;this.approval=approval;this.bodyClearApproval=bodyClearApproval;
            this.certificate=certificate;this.maintenance=maintenance;
            this.deadline=deadline;this.expected=expected;this.mainSha=mainSha;this.authoritySha=authoritySha;
        }
        void requireCurrent() {
            if(!active || thread!=Thread.currentThread())throw unavailable();
            owner.requirePools();Instant cutoff=phase==null?deadline.until():phase.cutoff;deadline.remaining(cutoff);
            if(phase==null)outside();else phase.requireCurrent(this);
            requireApproval(approval,bodyClearApproval);owner.validCertificate(certificate);
            try {
                if(maintenance.isClosed() || maintenance.getAutoCommit()
                        || maintenance.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED
                        || !authoritySha.equals(databaseIdentitySha(maintenance)))throw unavailable();
                String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                Maintainer current=owner.read(maintenance,deadline,cutoff,false);
                String subject=approval!=null?approval.evidence().operatorSubject():bodyClearApproval.evidence().operatorSubject();
                String organization=approval!=null?approval.evidence().operatorOrganization():bodyClearApproval.evidence().operatorOrganization();
                String certificateSha=approval!=null?approval.evidence().certificateSha():bodyClearApproval.evidence().certificateSha();
                long version=approval!=null?approval.evidence().maintainerVersion():bodyClearApproval.evidence().maintainerVersion();
                String primarySha=approval!=null?approval.evidence().primaryDatabaseIdentitySha():bodyClearApproval.evidence().primaryDatabaseIdentitySha();
                String independentSha=approval!=null?approval.evidence().authorityDatabaseIdentitySha():bodyClearApproval.evidence().authorityDatabaseIdentitySha();
                if(!expected.equals(current) || !"ACTIVE".equals(current.status()) || current.version()<1
                        || approval!=null&&(current.roles()==null || !List.of(current.roles().split(",")).contains("GRANTS"))
                        || !Objects.equals(current.subject(),subject)
                        || !Objects.equals(current.organization(),organization)
                        || !Objects.equals(current.certificateSha(),fingerprint)
                        || !fingerprint.equals(certificateSha) || current.version()!=version
                        || !mainSha.equals(primarySha)
                        || !authoritySha.equals(independentSha))throw denied();
            }catch(SQLException|java.security.GeneralSecurityException failed){throw failure("Registration lease observation unavailable",failed);}
            requireApproval(approval,bodyClearApproval);deadline.remaining(cutoff);
            if(phase!=null)phase.requireCurrent(this);
        }
        <T>T main(Instant cutoff,Supplier<T> operation) {
            return ProductPromptRegistrationTransactions.execute(this,owner.mainSource,owner.mainManager,cutoff,operation);
        }
        <T>T authority(Instant cutoff,Supplier<T> operation) {
            return ProductPromptRegistrationTransactions.execute(this,owner.authoritySource,owner.authorityManager,cutoff,operation);
        }
        void requireMainTransaction(DataSource expectedSource){requireTransaction(expectedSource,owner.mainSource);}
        void requireAuthorityTransaction(DataSource expectedSource){requireTransaction(expectedSource,owner.authoritySource);}
        private void requireTransaction(DataSource supplied,DataSource expectedSource) {
            requireCurrent();
            if(supplied!=expectedSource || phase==null || phase.source!=expectedSource)throw unavailable();
        }
        String identity(HikariDataSource source){return source==owner.mainSource?mainSha:authoritySha;}
        void invalidate(){active=false;}
        String subject(){requireCurrent();return expected.subject();}
        String organization(){requireCurrent();return expected.organization();}
        String certificateSha(){requireCurrent();return expected.certificateSha();}
        long maintainerVersion(){requireCurrent();return expected.version();}
        String primaryDatabaseIdentitySha(){requireCurrent();return mainSha;}
        String authorityDatabaseIdentitySha(){requireCurrent();return authoritySha;}
        Instant expiresAt(){requireCurrent();return deadline.until();}
        ProductPromptRegisterApproval.Witness approval(){requireCurrent();if(approval==null)throw denied();return approval;}
        ProductPromptBodyClearApproval.Witness bodyClearApproval(){requireCurrent();if(bodyClearApproval==null)throw denied();return bodyClearApproval;}
    }
}
