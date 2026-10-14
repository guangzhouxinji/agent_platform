package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkReadSources.minimum;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.ProductTrademarkReadBoundaryPort;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Owns the complete ordinary read; no maintenance certificate or write permission is requested. */
final class ProductTrademarkReadTransaction implements ProductTrademarkReadBoundaryPort {
    record Response<T>(String taskId,long taskVersion,Instant expiresAt,T data) { }
    record Loaded(ProductTrademarkReadSources.Witness source,ProductTrademarkReadCaptures.Capture capture) { }
    final class Context {
        final AuthorizedScope scope;
        final Map<String,Loaded> sources;
        final Object holder;
        final Connection connection;
        final Thread thread=Thread.currentThread();
        final long started=System.nanoTime();
        Instant until;
        Context(AuthorizedScope scope,Map<String,Loaded> sources) {
            this.scope=scope;this.sources=Map.copyOf(sources);this.until=scope.validUntil();
            holder=TransactionSynchronizationManager.getResource(db.getDataSource());connection=store.transaction();
        }
        void bound() {
            require(current.get()==this&&thread==Thread.currentThread()&&holder==TransactionSynchronizationManager.getResource(db.getDataSource())
                    &&connection==store.transaction(),Code.ACCESS_DENIED);
            require(System.nanoTime()-started<30_000_000_000L,Code.SOURCE_TIMEOUT);
            require(clock.instant().isBefore(until),Code.SOURCE_UNAVAILABLE);
        }
        void fields(String id,Set<Field> fields,String use) {
            bound(); Loaded loaded=sources.get(id);require(loaded!=null,Code.ACCESS_DENIED);
            until=minimum(until,sourceAuthority.requireFields(loaded.source(),fields,use));bound();
        }
        List<ProductTrademarkProjectionStore.Selection> selections() {
            bound();return sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getValue().capture().selection()).toList();
        }
    }
    private record Delivered(String task,long version,Instant until) { }
    private final JdbcTemplate db;
    private final ProductGrantWatermark grants;
    private final ProductConsultationAccess access;
    private final JwtDecoder decoder;
    private final String issuer;
    private final Clock clock;
    private final JdbcProductTaskDirectory tasks;
    private final ProductHumanIdentityLocks identities;
    private final TransactionTemplate transactions;
    private final ProductTrademarkReadSources sourceAuthority;
    private final ProductTrademarkReadCaptures captures;
    private final ProductTrademarkProjectionStore store;
    private final ThreadLocal<Context> current=new ThreadLocal<>();
    private final ThreadLocal<Delivered> completed=new ThreadLocal<>();
    private final ThreadLocal<Boolean> enveloping=new ThreadLocal<>();
    ProductTrademarkReadTransaction(JdbcTemplate db,DataSourceTransactionManager manager,ProductGrantWatermark grants,
            ProductConsultationAccess access,JwtDecoder decoder,String issuer,Clock clock,ProductTrademarkReadCaptures captures,
            ProductTrademarkProjectionStore store) {
        require(db.getDataSource()!=null&&manager.getDataSource()==db.getDataSource()&&grants.mainDataSource()==db.getDataSource()
                &&access.mainDataSource()==db.getDataSource()&&store.belongsTo(db.getDataSource()),Code.CAPABILITY_UNAVAILABLE);
        this.db=new JdbcTemplate(db.getDataSource());this.db.setQueryTimeout(5);this.grants=grants;this.access=access;this.decoder=decoder;this.issuer=issuer;this.clock=clock;this.captures=captures;this.store=store;
        require(db.getDataSource()!=grants.independentDatabase().getDataSource()
                &&!ProductTrademarkSourceProjectionAuthority.databaseIdentitySHA(db).equals(ProductTrademarkSourceProjectionAuthority.databaseIdentitySHA(grants.independentDatabase())),Code.CAPABILITY_UNAVAILABLE);
        tasks=new JdbcProductTaskDirectory(this.db,clock);identities=new ProductHumanIdentityLocks(this.db,issuer,clock);
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(30);
        var sourceWatermark=new ProductSourceWatermark(db,grants.independentDatabase(),new TransactionTemplate(manager));
        sourceAuthority=new ProductTrademarkReadSources(db,sourceWatermark,clock,captures.sourceIds());
    }
    <T> Response<T> response(Supplier<T> operation) {
        require(enveloping.get()==null&&current.get()==null,Code.ACCESS_DENIED);enveloping.set(true);
        try { T value=operation.get();Delivered result=completed.get();require(result!=null&&clock.instant().isBefore(result.until()),Code.SOURCE_UNAVAILABLE);
            return new Response<>(result.task(),result.version(),result.until(),value);
        } finally {completed.remove();enveloping.remove();}
    }
    Context context(AuthorizedScope scope) { Context context=current.get();require(context!=null&&context.scope==scope,Code.ACCESS_DENIED);context.bound();return context; }
    @Override public <T> T read(Request request,Function<AuthorizedScope,T> callback) {
        require(!TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.isSynchronizationActive()
                &&TransactionSynchronizationManager.getResourceMap().isEmpty()&&current.get()==null,Code.ACCESS_DENIED);
        require(request.operation()==Operation.QUERY||request.operation()==Operation.DETAIL,Code.CAPABILITY_UNAVAILABLE);
        Jwt jwt=verified(request.credential());long started=System.nanoTime();
        final Delivered[] delivered={null};
        T output=transactions.execute(status->{
            long generation=grants.currentGeneration();
            one("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",request.taskId());
            var task=task(request.taskId());one("SELECT organization_id FROM con3_organization WHERE organization_id=? AND status='ACTIVE' FOR UPDATE",task.organization());
            var identity=identity(jwt);permit(jwt,task);
            var plan=sourceAuthority.plan(task,jwt);sourceAuthority.lock(task,jwt,plan);permit(jwt,task);
            var loaded=new LinkedHashMap<String,Loaded>();var scopes=new ArrayList<SourceScope>();
            Instant until=minimum(task.expiresAt(),jwt.getExpiresAt(),identity.session().expires(),plan.until());
            if("VERIFY_ONLY".equals(identity.keyStatus()))until=minimum(until,identity.verifyUntil());
            for(var selected:plan.scopes()) {
                var source=sourceAuthority.witness(selected);var capture=captures.read(task,source.active());
                var verified=store.verify(capture.selection());
                loaded.put(selected.source(),new Loaded(source,capture));until=minimum(until,source.until(),capture.stamp().until(),verified.expiresAt());
                scopes.add(new SourceScope(new SourceVersion(selected.source(),source.active().sourceVersion()),sourceAuthority.displayFields(source)));
            }
            require(System.nanoTime()-started<30_000_000_000L,Code.SOURCE_TIMEOUT);
            var scope=new AuthorizedScope(task.id(),task.version(),issuer,jwt.getSubject(),task.organization(),generation,
                    loaded.values().stream().mapToLong(v->v.source().active().epoch()).min().orElseThrow(),until,scopes);
            Context context=new Context(scope,loaded);current.set(context);
            try {
                T result=callback.apply(scope);context.bound();
                for(Loaded expected:loaded.values()) {
                    require(expected.source().equals(sourceAuthority.witness(expected.source().scope())),Code.SOURCE_UNAVAILABLE);
                    var again=captures.read(task,expected.source().active());require(expected.capture().stamp().equals(again.stamp()),Code.SOURCE_UNAVAILABLE);
                    context.until=minimum(context.until,store.verify(again.selection()).expiresAt());context.bound();
                }
                Jwt finalJwt=verified(request.credential());
                require(task.equals(task(request.taskId()))&&identity.equals(identity(finalJwt)),Code.ACCESS_DENIED);
                permit(finalJwt,task);require(plan.equals(sourceAuthority.plan(task,finalJwt)),Code.ACCESS_DENIED);
                // Source/Task/identity checks follow every potentially long evidence read.
                for(Loaded expected:loaded.values())require(expected.source().equals(sourceAuthority.witness(expected.source().scope())),Code.SOURCE_UNAVAILABLE);
                grants.assertUnchanged(generation);context.bound();
                require(System.nanoTime()-started<30_000_000_000L,Code.SOURCE_TIMEOUT);
                delivered[0]=new Delivered(task.id(),task.version(),context.until);return result;
            } finally {current.remove();}
        });
        require(delivered[0]!=null&&clock.instant().isBefore(delivered[0].until()),Code.SOURCE_UNAVAILABLE);
        if(Boolean.TRUE.equals(enveloping.get()))completed.set(delivered[0]);return output;
    }
    private Jwt verified(String token) {
        try {
            require(token!=null&&!token.isBlank(),Code.UNAUTHENTICATED);Jwt jwt=decoder.decode(token);
            require(jwt.getIssuer()!=null&&issuer.equals(jwt.getIssuer().toString())&&jwt.getSubject()!=null&&jwt.getId()!=null
                    &&jwt.getClaimAsString("sid")!=null&&jwt.getHeaders().get("kid") instanceof String
                    &&jwt.getExpiresAt()!=null&&clock.instant().isBefore(jwt.getExpiresAt())
                    &&(jwt.getNotBefore()==null||!clock.instant().isBefore(jwt.getNotBefore())),Code.UNAUTHENTICATED);return jwt;
        }catch(JwtException failure){throw new Failure(Code.UNAUTHENTICATED);}
    }
    private ProductTaskDirectory.Task task(String id) {
        var task=tasks.find(id).orElseThrow(()->new Failure(Code.ACCESS_DENIED));
        require(!"CANCELLED".equals(task.state())&&clock.instant().isBefore(task.expiresAt()),Code.ACCESS_DENIED);return task;
    }
    private ProductHumanIdentityLocks.Identity identity(Jwt jwt) {
        try{return identities.read(jwt,true);}catch(IllegalStateException failure){
            if("Current human session required".equals(failure.getMessage()))throw new Failure(Code.UNAUTHENTICATED);
            if("Current human identity unavailable".equals(failure.getMessage()))throw new Failure(Code.ACCESS_DENIED);
            throw failure;
        }
    }
    private void permit(Jwt jwt,ProductTaskDirectory.Task task) {
        require(access.permits(jwt,new ProductConsultationAccess.ResourceFacts(task.organization(),task.id(),task.dataScope(),"TASK",task.id(),task.authorizationVersion()),ProductAccessPolicy.Action.READ),Code.ACCESS_DENIED);
    }
    private void one(String sql,Object... args) { require(db.queryForList(sql,String.class,args).size()==1,Code.ACCESS_DENIED); }
}
