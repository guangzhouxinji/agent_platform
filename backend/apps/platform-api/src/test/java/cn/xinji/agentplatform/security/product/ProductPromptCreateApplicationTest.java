package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptCreateApplicationTest {
    @TempDir Path root;
    private Fixture f;
    @BeforeEach void setup()throws Exception{f=new Fixture(root);}

    @Test void createsAndReopensOriginalKeyWithOnlyCreateGrantAndEveryQueryActuallyBounded(){
        Receipt result=f.app().request(f.base.token,f.base.workspaceId,"create-one",f.input.command);
        Receipt reopened=f.app().lookup(f.base.token,f.base.workspaceId,"create-one");
        assertThat(reopened).isEqualTo(result);
        assertThat(f.app().request(f.base.token,f.base.workspaceId,"create-one",f.input.command)).isEqualTo(result);
        for(String table:List.of("conp_prompt","conp_revision","conp_event","conp_receipt"))assertThat(f.mainCount(table)).isEqualTo(1);
        for(String table:List.of("idp_prompt_request","idp_prompt_control","idp_revision_witness"))assertThat(f.authorityCount(table)).isEqualTo(1);
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("READY");
        assertThat(f.mainSource.timeouts).isNotEmpty().allMatch(n->n>=1&&n<=5);
        assertThat(f.authoritySource.timeouts).isNotEmpty().allMatch(n->n>=1&&n<=5);
        assertThat(f.mainSource.statements).anyMatch(s->s.startsWith("SELECT kid,public_spki"));
        assertThat(f.mainSource.statements).anyMatch(s->s.startsWith("SELECT COUNT(*) FROM id2_session"));
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("SELECT revision_json"));
    }

    @Test void independentReserveSurvivesMainFailureAndRebuiltApplicationUsesSamePrepared(){
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"recover-before",f.input.command)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainCount("conp_prompt")).isZero();assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);
        var original=f.authority.queryForMap("SELECT prompt_id,target_revision_id,receipt_id,prepared_at,body_until,audit_until,prepared_sha FROM idp_prompt_request");
        f.base.now=f.base.now.plusSeconds(20);
        Receipt result=f.app().request(f.base.token,f.base.workspaceId,"recover-before",f.input.command);
        assertThat(f.authority.queryForMap("SELECT prompt_id,target_revision_id,receipt_id,prepared_at,body_until,audit_until,prepared_sha FROM idp_prompt_request"))
                .isEqualTo(original);
        assertThat(result.id()).isEqualTo(original.get("RECEIPT_ID"));assertThat(f.mainCount("conp_revision")).isEqualTo(1);
    }

    @Test void committedMainWithLostReadySettlesAfterIntentAndBodyExpiryWithoutReadingBody(){
        f.authoritySource.failOnce(sql->sql.startsWith("INSERT INTO idp_revision_witness"));
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"recover-after",f.input.command)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainCount("conp_receipt")).isEqualTo(1);assertThat(f.authorityCount("idp_revision_witness")).isZero();
        f.base.now=f.base.now.plusSeconds(601);f.mainSource.statements.clear();
        Receipt result=f.app().lookup(f.base.token,f.base.workspaceId,"recover-after");
        assertThat(result).isNotNull();assertThat(f.mainCount("conp_revision")).isEqualTo(1);
        assertThat(f.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("READY");
    }

    @Test void expiredIntentCannotResumeAllBeforeAndLookupNeverWritesBody(){
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"expired-before",f.input.command)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainSource.faultTriggered).isTrue();
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        assertThatThrownBy(()->f.app().lookup(f.base.token,f.base.workspaceId,"expired-before")).isInstanceOf(IllegalStateException.class);
        f.base.now=f.base.now.plusSeconds(300);
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"expired-before",f.input.command)).isInstanceOf(IllegalStateException.class);
        assertThat(f.mainCount("conp_prompt")).isZero();assertThat(f.authorityCount("idp_revision_witness")).isZero();
    }

    @Test void sameKeyDifferentCommandHasTypedConflictWithoutAnotherRequest(){
        f.app().request(f.base.token,f.base.workspaceId,"conflict",f.input.command);
        var c=f.input.command;
        var different=new ProductPromptCreateCommand(c.expectedGeneration(),c.kind(),c.name(),c.body()+" changed",c.scenarioIds(),c.intentIds(),
                c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"conflict",different)).isInstanceOf(ProductPromptCreateConflict.class);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);
    }

    @Test void missingRequestAndRevokedCreateAreDeniedRatherThanCreatingAuthority(){
        assertThatThrownBy(()->f.app().lookup(f.base.token,f.base.workspaceId,"missing")).isInstanceOf(ProductPromptAccessDenied.class);
        f.base.revoke(Action.CREATE);
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"denied",f.input.command)).isInstanceOf(ProductPromptAccessDenied.class);
        assertThat(f.authorityCount("idp_prompt_request")).isZero();
    }

    @Test void revokedOriginalPermissionClosesPreparedRecovery()throws Exception{
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        var app=f.app();assertThatThrownBy(()->app.request(f.base.token,f.base.workspaceId,"source-closed",f.input.command)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainSource.faultTriggered).isTrue();
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        f.input.permission.put("status","REVOKED");f.input.install();
        assertThatThrownBy(()->app.request(f.base.token,f.base.workspaceId,"source-closed",f.input.command)).isInstanceOf(IllegalStateException.class);
        assertThat(f.mainCount("conp_revision")).isZero();
    }

    @Test void grantExpiresAfterWitnessSqlButBeforeAuthorityCommitRollsBackWholeReady(){
        var cutoff=f.base.now.plusSeconds(10);
        f.base.watermark.change(()->f.base.db.update("UPDATE id2_grant SET expires_at=? WHERE grant_id=?",
                java.sql.Timestamp.from(cutoff),f.base.receipts.get(f.base.workspaceId+":CREATE").grantId()));
        f.authoritySource.afterSql=sql->{if(sql.startsWith("INSERT INTO idp_revision_witness"))f.base.now=cutoff;};
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"grant-cutoff",f.input.command)).isInstanceOf(RuntimeException.class);
        assertThat(f.base.now).isEqualTo(cutoff);
        assertThat(f.mainCount("conp_receipt")).isEqualTo(1);
        assertThat(f.authorityCount("idp_revision_witness")).isZero();
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("PENDING");
    }

    @Test void inputDeadlineReachedAfterReserveSqlRollsBackBothIndependentRows()throws Exception{
        var cutoff=f.base.now.plusSeconds(10);
        f.input.permission.put("bodyUntil",cutoff);f.input.install();
        f.authoritySource.afterSql=sql->{if(sql.startsWith("INSERT INTO idp_prompt_request"))f.base.now=cutoff;};
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,"input-cutoff",f.input.command)).isInstanceOf(RuntimeException.class);
        assertThat(f.base.now).isEqualTo(cutoff);
        assertThat(f.mainCount("conp_prompt")).isZero();
        assertThat(f.authorityCount("idp_prompt_request")).isZero();assertThat(f.authorityCount("idp_prompt_control")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"","has space","with\ttab","含中文","abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"})
    void invalidOriginalKeyIsRejectedBeforeAnyReserve(String key){
        assertThatThrownBy(()->f.app().request(f.base.token,f.base.workspaceId,key,f.input.command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.authorityCount("idp_prompt_request")).isZero();
    }

    @Test void explicitMainSuspensionRejectsLeaseAndStillBlocksAnotherPhysicalWorkspaceWriter()throws Exception{
        var auth=f.authorization();var boundary=new ProductPromptAuthorityTransactions(f.store,f.control,f.mainManager,f.authorityManager,f.base.clock);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            auth.execute(f.base.token,f.base.workspaceId,Action.CREATE,lease->{
                Object holder=TransactionSynchronizationManager.getResource(f.mainSource);
                Object reader=f.control.jdbc.requireNoManagedTransaction();
                assertThat(reader).isInstanceOf(ConnectionHolder.class);
                String value=boundary.execute(lease,()->{
                    assertThat(TransactionSynchronizationManager.hasResource(f.mainSource)).isFalse();
                    assertThatThrownBy(lease::requireCurrent).isInstanceOf(IllegalStateException.class);
                    Future<Boolean> blocked=worker.submit(()->{
                        try(Connection c=f.base.mainSource.getConnection();Statement config=c.createStatement()){
                            c.setAutoCommit(false);config.execute("SET LOCK_TIMEOUT 100");
                            try(PreparedStatement update=c.prepareStatement("UPDATE conp_workspace SET state='CLOSED' WHERE workspace_id=?")){
                                update.setString(1,f.base.workspaceId);update.executeUpdate();c.rollback();return false;
                            }catch(SQLException expected){c.rollback();return expected.getErrorCode()==50200;}
                        }
                    });
                    try{assertThat(blocked.get(3,TimeUnit.SECONDS)).isTrue();}catch(Exception e){throw new IllegalStateException(e);}
                    return "independent";
                });
                assertThat(TransactionSynchronizationManager.getResource(f.mainSource)).isSameAs(holder);
                assertThat(f.control.jdbc.requireNoManagedTransaction()).isSameAs(reader);
                lease.requireCurrent();assertThat(value).isEqualTo("independent");return null;
            });
        }finally{worker.shutdownNow();}
    }

    @Test void outerMainTransactionIsRejected(){
        new TransactionTemplate(f.mainManager).executeWithoutResult(status->{
            assertThatThrownBy(()->f.app().lookup(f.base.token,f.base.workspaceId,"nested")).isInstanceOf(IllegalStateException.class);
        });
    }

    @ParameterizedTest @ValueSource(strings={"unsynchronized","active-transaction","borrowed","wrong-source","wrong-resource","rollback-only"})
    void authorityReaderCheckRejectsUnsafeBoundResources(String condition)throws Exception{
        f.authorization().execute(f.base.token,f.base.workspaceId,Action.CREATE,lease->{
            Object original=TransactionSynchronizationManager.unbindResource(f.authoritySource);
            try(Connection connection=("wrong-source".equals(condition)?f.mainSource:f.authoritySource).getConnection()){
                ConnectionHolder replacement=new ConnectionHolder(connection);
                replacement.setSynchronizedWithTransaction(!"unsynchronized".equals(condition));
                if("active-transaction".equals(condition))connection.setAutoCommit(false);
                if("borrowed".equals(condition))replacement.requested();
                if("rollback-only".equals(condition))replacement.setRollbackOnly();
                TransactionSynchronizationManager.bindResource(f.authoritySource,"wrong-resource".equals(condition)?new Object():replacement);
                assertThatThrownBy(f.control.jdbc::requireNoManagedTransaction).isInstanceOf(IllegalStateException.class);
                if(!connection.getAutoCommit())connection.rollback();
            }catch(SQLException error){throw new IllegalStateException(error);}
            finally{
                TransactionSynchronizationManager.unbindResourceIfPossible(f.authoritySource);
                TransactionSynchronizationManager.bindResource(f.authoritySource,original);
            }
            lease.requireCurrent();return null;
        });
    }

    static final class Fixture {
        final ProductPromptWorkspaceAuthorizationFixture base;
        final ProductPromptInputPermissionFixture input;
        final TrackingSource mainSource,authoritySource;
        final ProductPromptStatementJdbcTemplate main,authority;
        final DataSourceTransactionManager mainManager,authorityManager;
        final ProductPromptStore store;
        final ProductPromptAuthorityStore control;
        final ProductPromptCreateRequestStore requests;
        final ProductGrantWatermark watermark;
        final ProductConsultationAccess access;
        final org.springframework.security.oauth2.jwt.JwtDecoder decoder;
        final ProductPromptBindingRegistry registry;

        Fixture(Path root)throws Exception{
            base=new ProductPromptWorkspaceAuthorizationFixture(root);base.grant(Action.CREATE);
            migrate(base.authoritySource,"identity-authority/V008__product_prompt_create_request.sql");
            input=new ProductPromptInputPermissionFixture(base);
            mainSource=new TrackingSource(base.mainSource);authoritySource=new TrackingSource(base.authoritySource);
            main=new ProductPromptStatementJdbcTemplate(mainSource);authority=new ProductPromptStatementJdbcTemplate(authoritySource);
            mainManager=new DataSourceTransactionManager(mainSource);authorityManager=new DataSourceTransactionManager(authoritySource);
            store=new ProductPromptStore(main,mainManager,base.mapper,base.clock);
            control=new ProductPromptAuthorityStore(authority,authorityManager,base.clock);
            requests=new ProductPromptCreateRequestStore(control,base.mapper,base.clock);
            watermark=new ProductGrantWatermark(main,authority,ProductPromptWorkspaceAuthorizationFixture.ISSUER);
            access=new ProductConsultationAccess(main,watermark,ProductPromptWorkspaceAuthorizationFixture.ISSUER,base.clock);
            var keys=new ProductSigningKeys(main,new TransactionTemplate(mainManager),ProductPromptWorkspaceAuthorizationFixture.ISSUER,
                    base.masterKeyBase64,Duration.ofMinutes(5),base.clock);
            decoder=new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys,main,watermark,
                    ProductPromptWorkspaceAuthorizationFixture.ISSUER,ProductPromptWorkspaceAuthorizationFixture.AUDIENCE);
            String ref="create-workspace-bindings.json",sha=base.write(ref,Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(base.bindings.values())));
            registry=new ProductPromptBindingRegistry(new ProductSourceEvidenceFiles(root,1048576),ref,sha);
            mainSource.timeouts.clear();authoritySource.timeouts.clear();mainSource.statements.clear();authoritySource.statements.clear();
        }
        ProductPromptWorkspaceAuthorization authorization(){return new ProductPromptWorkspaceAuthorization(store,control,mainManager,
                registry.evidence(),watermark,access,decoder,ProductPromptWorkspaceAuthorizationFixture.ISSUER,base.clock);}
        ProductPromptCreateApplication app(){return new ProductPromptCreateApplication(store,control,requests,authorization(),input.adapter(),registry,
                mainManager,authorityManager,base.clock);}
        long mainCount(String table){return base.db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
        long authorityCount(String table){return base.independent.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    }

    static final class TrackingSource extends DelegatingDataSource {
        final List<Integer> timeouts=Collections.synchronizedList(new ArrayList<>());
        final List<String> statements=Collections.synchronizedList(new ArrayList<>());
        private Predicate<String> fault=sql->false;
        Consumer<String> afterSql=sql->{};
        private final AtomicBoolean armed=new AtomicBoolean();
        boolean faultTriggered;
        TrackingSource(DataSource target){super(target);}
        void failOnce(Predicate<String> value){fault=value;faultTriggered=false;armed.set(true);}
        @Override public Connection getConnection()throws SQLException{return wrap(super.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(super.getConnection(user,password));}
        private Connection wrap(Connection real){
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                Object result=invoke(real,m,args);
                if(result instanceof Statement statement){
                    String sql=args!=null&&args.length>0&&args[0] instanceof String text?text:"";int[] timeout={0};
                    Class<?> contract=statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{contract},(sp,sm,sa)->{
                        if(sm.getName().equals("setQueryTimeout"))timeout[0]=(int)sa[0];
                        String executed=null;
                        if(sm.getName().startsWith("execute")){
                            String actual=sql.isEmpty()&&sa!=null&&sa.length>0&&sa[0] instanceof String text?text:sql;
                            timeouts.add(timeout[0]);statements.add(actual);
                            if(armed.get()&&fault.test(actual)&&armed.compareAndSet(true,false)){
                                faultTriggered=true;throw new SQLException("Injected component SQL interruption","HY000");
                            }
                            executed=actual;
                        }
                        Object value=invoke(statement,sm,sa);if(executed!=null)afterSql.accept(executed);return value;
                    });
                }
                return result;
            });
        }
        private static Object invoke(Object target,java.lang.reflect.Method method,Object[] args)throws Throwable{
            try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
        }
    }
}
