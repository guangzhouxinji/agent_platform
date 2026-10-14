package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import java.util.Objects;
import java.util.function.Supplier;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;

/** Suspends the actual main holder, retaining its physical transaction and workspace lock. */
final class ProductPromptAuthorityTransactions {
    private final ProductPromptStore main;
    private final ProductPromptAuthorityStore authority;
    private final TransactionTemplate suspended,independent;
    private final Clock clock;

    ProductPromptAuthorityTransactions(ProductPromptStore main,ProductPromptAuthorityStore authority,
            DataSourceTransactionManager mainManager,DataSourceTransactionManager authorityManager,Clock clock){
        this.main=Objects.requireNonNull(main);this.authority=Objects.requireNonNull(authority);
        this.clock=Objects.requireNonNull(clock);
        if(mainManager.getDataSource()!=main.jdbc.source||authorityManager.getDataSource()!=authority.jdbc.source)throw invalid();
        main.jdbc.requireSeparate(authority.jdbc);
        suspended=new TransactionTemplate(mainManager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        independent=new TransactionTemplate(authorityManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent.setTimeout(30);
    }

    <T>T execute(ProductPromptWorkspaceAuthorization.Lease lease,Supplier<T> callback){
        return execute(lease,lease.cutoff(),callback);
    }
    <T>T execute(ProductPromptWorkspaceAuthorization.Lease lease,Instant inputCutoff,Supplier<T> callback){
        Objects.requireNonNull(callback);lease.requireCurrent();main.jdbc.requireTransaction();
        Objects.requireNonNull(inputCutoff);Instant admissionCutoff=lease.cutoff();
        Instant cutoff=inputCutoff.isBefore(admissionCutoff)?inputCutoff:admissionCutoff;
        current(cutoff);
        Object authorityReader=authority.jdbc.requireNoManagedTransaction();
        Object holder=TransactionSynchronizationManager.getResource(main.jdbc.source);
        Throwable failure=null;
        try{
            return suspended.execute(outer->{
                if(TransactionSynchronizationManager.hasResource(main.jdbc.source)
                        ||TransactionSynchronizationManager.isActualTransactionActive()
                        ||TransactionSynchronizationManager.hasResource(authority.jdbc.source))throw unavailable();
                return independent.execute(inner->{
                    if(TransactionSynchronizationManager.hasResource(main.jdbc.source))throw unavailable();
                    authority.jdbc.requireTransaction();current(cutoff);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                        @Override public void beforeCommit(boolean readOnly){current(cutoff);}
                    });
                    T result=callback.get();
                    authority.jdbc.requireTransaction();current(cutoff);
                    if(TransactionSynchronizationManager.hasResource(main.jdbc.source))throw unavailable();
                    return result;
                });
            });
        }catch(RuntimeException|Error error){failure=error;throw error;}
        finally{
            try{
                if(holder!=TransactionSynchronizationManager.getResource(main.jdbc.source)
                        ||authorityReader!=TransactionSynchronizationManager.getResource(authority.jdbc.source))throw unavailable();
                main.jdbc.requireTransaction();authority.jdbc.requireNoManagedTransaction();lease.requireCurrent();
                authority.jdbc.requireNoManagedTransaction();
            }catch(RuntimeException|Error restored){if(failure!=null)failure.addSuppressed(restored);else throw restored;}
        }
    }
    private void current(Instant cutoff){if(!clock.instant().isBefore(cutoff))throw unavailable();}
}
