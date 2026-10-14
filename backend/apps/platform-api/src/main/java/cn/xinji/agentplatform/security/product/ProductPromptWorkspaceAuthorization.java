package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Current workspace admission only; no registry writer, TEST admission or cross-store business commit. */
final class ProductPromptWorkspaceAuthorization {
    private final ProductPromptStore store;
    private final ProductPromptAuthorityStore authority;
    private final ProductPromptWorkspaceEvidence evidence;
    private final ProductGrantWatermark watermark;
    private final ProductHumanIdentityLocks identities;
    private final ProductPromptWorkspaceGrants grants;
    private final JwtDecoder decoder;
    private final TransactionTemplate transactions;
    private final String issuer;
    private final Clock clock;

    ProductPromptWorkspaceAuthorization(ProductPromptStore store,ProductPromptAuthorityStore authority,
            DataSourceTransactionManager manager,ProductPromptWorkspaceEvidence evidence,ProductGrantWatermark watermark,
            ProductConsultationAccess access,JwtDecoder decoder,String issuer,Clock clock){
        this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);
        if(manager.getDataSource()!=store.jdbc.source||access.mainDataSource()!=store.jdbc.source
                ||watermark.mainDataSource()!=store.jdbc.source
                ||watermark.independentDatabase().getDataSource()!=authority.jdbc.source)throw invalid();
        store.jdbc.requireSeparate(authority.jdbc);
        this.evidence=Objects.requireNonNull(evidence);this.watermark=watermark;
        this.decoder=Objects.requireNonNull(decoder);text(issuer);this.issuer=issuer;this.clock=Objects.requireNonNull(clock);
        identities=new ProductHumanIdentityLocks(store.jdbc.db,issuer,clock);
        grants=new ProductPromptWorkspaceGrants(store.jdbc.db,access,clock);
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
    }
    <T>T execute(String rawToken,String workspaceId,Action action,Function<Lease,T> operation){
        uuid(workspaceId);Objects.requireNonNull(action);
        if(action==Action.COPY)throw invalid();
        return boundary(rawToken,workspaceId,null,action,operation);
    }
    <T>T executeCopy(String rawToken,String sourceWorkspaceId,String targetWorkspaceId,Function<Lease,T> operation){
        uuid(sourceWorkspaceId);uuid(targetWorkspaceId);
        return boundary(rawToken,targetWorkspaceId,sourceWorkspaceId,Action.COPY,operation);
    }
    private <T>T boundary(String raw,String target,String source,Action action,Function<Lease,T> operation){
        Objects.requireNonNull(operation);
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(store.jdbc.source)||TransactionSynchronizationManager.hasResource(authority.jdbc.source))
            throw unavailable();
        Jwt jwt=verified(raw);
        return transactions.execute(status->{
            store.jdbc.requireTransaction();long generation=watermark.currentGeneration();
            var workspaces=new TreeMap<String,Workspace>();
            var ids=new java.util.TreeSet<String>();ids.add(target);if(source!=null)ids.add(source);
            for(String id:ids)evidence.requireBound(id);
            for(String id:ids){Workspace workspace=store.workspace(id,true);authority.requireWorkspace(workspace);workspaces.put(id,workspace);}
            if(source!=null&&!workspaces.get(source).organization().equals(workspaces.get(target).organization()))throw denied();
            var organizations=workspaces.values().stream().map(Workspace::organization).distinct().sorted().toList();
            for(String organization:organizations){
                var rows=store.jdbc.db.query("SELECT organization_id FROM con3_organization WHERE organization_id=? AND status='ACTIVE' FOR UPDATE",
                        (rs,n)->rs.getString(1),organization);
                if(rows.size()!=1)throw denied();
            }
            var identity=currentIdentity(jwt,true);
            var policies=new TreeMap<String,ProductPromptWorkspaceEvidence.Witness>();
            for(Workspace workspace:workspaces.values())policies.put(workspace.id(),evidence.current(workspace,clock.instant()));
            var coordinates=new ArrayList<ProductPromptWorkspaceGrants.Coordinate>();
            var destination=workspaces.get(target);evidence.requireAction(policies.get(target),action);
            coordinates.add(new ProductPromptWorkspaceGrants.Coordinate(destination,action));
            if(action==Action.PREVIEW){evidence.requireAction(policies.get(target),Action.READ);coordinates.add(new ProductPromptWorkspaceGrants.Coordinate(destination,Action.READ));}
            if(source!=null){evidence.requireAction(policies.get(source),Action.READ);coordinates.add(new ProductPromptWorkspaceGrants.Coordinate(workspaces.get(source),Action.READ));}
            coordinates.sort(Comparator.comparing((ProductPromptWorkspaceGrants.Coordinate c)->c.workspace().organization())
                    .thenComparing(ProductPromptWorkspaceGrants.Coordinate::resource).thenComparing(c->c.action().name()));
            var rights=coordinates.stream().map(c->grants.current(jwt,c,true)).toList();
            var lease=new Lease(raw,jwt,action,workspaces,policies,identity,rights,generation);
            try {lease.requireCurrent();T result=operation.apply(lease);lease.requireCurrent();return result;}
            finally {lease.active=false;}
        });
    }
    final class Lease {
        private final String raw;
        private final Jwt original;
        private final Action action;
        private final Map<String,Workspace> workspaces;
        private final Map<String,ProductPromptWorkspaceEvidence.Witness> policies;
        private final ProductHumanIdentityLocks.Identity identity;
        private final List<ProductPromptWorkspaceGrants.Witness> rights;
        private final long generation;
        private final Object holder=TransactionSynchronizationManager.getResource(store.jdbc.source);
        private final Thread thread=Thread.currentThread();
        private boolean active=true;
        private Lease(String raw,Jwt original,Action action,Map<String,Workspace> workspaces,
                Map<String,ProductPromptWorkspaceEvidence.Witness> policies,ProductHumanIdentityLocks.Identity identity,
                List<ProductPromptWorkspaceGrants.Witness> rights,long generation){
            this.raw=raw;this.original=original;this.action=action;this.workspaces=Map.copyOf(workspaces);
            this.policies=Map.copyOf(policies);this.identity=identity;this.rights=List.copyOf(rights);this.generation=generation;
        }
        Workspace workspace(String id){bound();Workspace value=workspaces.get(id);if(value==null)throw denied();return value;}
        ProductPromptWorkspaceEvidence.Witness workspaceEvidence(String id){
            bound();var value=policies.get(id);if(value==null)throw denied();return value;
        }
        String subject(){bound();return original.getSubject();}
        String issuer(){bound();return original.getIssuer().toString();}
        Instant cutoff(){
            bound();Instant until=original.getExpiresAt();
            if(identity.session().expires().isBefore(until))until=identity.session().expires();
            if("VERIFY_ONLY".equals(identity.keyStatus())&&identity.verifyUntil().isBefore(until))until=identity.verifyUntil();
            for(var right:rights)if(right.grant().until().isBefore(until))until=right.grant().until();
            for(var workspace:workspaces.values())if(workspace.validUntil().isBefore(until))until=workspace.validUntil();
            for(var policy:policies.values()){
                if(policy.approval().validUntil().isBefore(until))until=policy.approval().validUntil();
                if(policy.policy().validUntil().isBefore(until))until=policy.policy().validUntil();
            }
            return until;
        }
        Action action(){bound();return action;}
        void requireCopySource(String workspaceId){requireCopyRole(workspaceId,Action.READ);}
        void requireCopyTarget(String workspaceId){requireCopyRole(workspaceId,Action.COPY);}
        private void requireCopyRole(String workspaceId,Action required){
            bound();Workspace selected=workspace(workspaceId);
            if(action!=Action.COPY||rights.stream().noneMatch(right->right.coordinate().operation()==required
                    &&right.coordinate().workspace().equals(selected)))throw unavailable();
        }
        // This action authenticates both Grants, but carries no variable TEST policy or render authority.
        boolean testAdmitted(){bound();return false;}
        void requireCurrent(){
            bound();Jwt current=verified(raw);
            if(!original.getTokenValue().equals(current.getTokenValue())||!identity.equals(currentIdentity(current,false)))throw denied();
            for(Workspace expected:workspaces.values()){
                if(!expected.equals(store.workspace(expected.id(),false)))throw unavailable();
                authority.requireWorkspace(expected);
                if(!policies.get(expected.id()).equals(evidence.current(expected,clock.instant())))throw unavailable();
                var rows=store.jdbc.db.query("SELECT organization_id FROM con3_organization WHERE organization_id=? AND status='ACTIVE'",
                        (rs,n)->rs.getString(1),expected.organization());
                if(rows.size()!=1)throw denied();
            }
            for(var right:rights)if(!right.equals(grants.current(current,right.coordinate(),false)))throw denied();
            watermark.assertUnchanged(generation);
            Instant now=clock.instant();
            if(!now.isBefore(current.getExpiresAt())||!now.isBefore(identity.session().expires())
                    ||"VERIFY_ONLY".equals(identity.keyStatus())&&!now.isBefore(identity.verifyUntil()))throw denied();
            for(var right:rights)if(!now.isBefore(right.grant().until()))throw denied();
            for(var policy:policies.values())if(now.isBefore(policy.approval().validFrom())||now.isBefore(policy.policy().validFrom())
                    ||!now.isBefore(policy.approval().validUntil())||!now.isBefore(policy.policy().validUntil())
                    ||!now.isBefore(workspaces.get(policy.binding().workspaceId()).validUntil()))throw denied();
        }
        private void bound(){
            if(!active||thread!=Thread.currentThread()||holder!=TransactionSynchronizationManager.getResource(store.jdbc.source))throw unavailable();
            store.jdbc.requireTransaction();
        }
    }
    private ProductHumanIdentityLocks.Identity currentIdentity(Jwt jwt,boolean locking){
        try { return identities.read(jwt,locking); }
        catch(IllegalStateException failure){
            if("Current human identity unavailable".equals(failure.getMessage()))throw denied();
            if("Current human session required".equals(failure.getMessage()))throw new ProductPromptSessionUnavailable();
            throw failure;
        }
    }
    private Jwt verified(String raw){
        if(raw==null||raw.isBlank())throw denied();
        Jwt jwt=decoder.decode(raw);Instant now=clock.instant();
        if(jwt==null||jwt.getIssuer()==null||!issuer.equals(jwt.getIssuer().toString())
                ||jwt.getSubject()==null||jwt.getSubject().isBlank()||jwt.getId()==null||jwt.getId().isBlank()
                ||jwt.getClaimAsString("sid")==null||jwt.getClaimAsString("sid").isBlank()
                ||!(jwt.getHeaders().get("kid") instanceof String kid)||kid.isBlank()
                ||jwt.getExpiresAt()==null||!now.isBefore(jwt.getExpiresAt())
                ||jwt.getNotBefore()!=null&&now.isBefore(jwt.getNotBefore()))throw denied();
        return jwt;
    }
}
