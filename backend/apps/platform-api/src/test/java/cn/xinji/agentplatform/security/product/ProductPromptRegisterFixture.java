package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Empty workspace and owned engineering certificates, never runtime admission evidence. */
final class ProductPromptRegisterFixture implements AutoCloseable {
    enum Side { MAIN, AUTHORITY }
    Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}};
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final String workspaceId=id(),organization=id(),subject="owned-register-maintainer";
    final Path root;
    final FaultSource mainFault=new FaultSource(),authorityFault=new FaultSource();
    final HikariDataSource mainSource=pool(mainFault,2),authoritySource=pool(authorityFault,3);
    final JdbcTemplate db=new ProductPromptStatementJdbcTemplate(mainSource),independent=new ProductPromptStatementJdbcTemplate(authoritySource);
    final DataSourceTransactionManager mainManager=new DataSourceTransactionManager(mainSource),authorityManager=new DataSourceTransactionManager(authoritySource);
    final ProductPromptStore store=new ProductPromptStore(db,mainManager,mapper,clock);
    final ProductPromptAuthorityStore control=new ProductPromptAuthorityStore(independent,authorityManager,clock);
    final X509Certificate certificate;
    final String certificateSha;
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final ProductPromptRegisterApproval approval;
    final ProductPromptRegistrationLease lease;
    final ProductPromptRegisterStore requests;
    final ProductPromptRegisterReconciliation reconciliation;
    final ProductPromptRegisterApplication app;
    private final List<ProductPromptRegistrationLease> reopened=new ArrayList<>();
    final ProductPromptRecords.Workspace target;
    final String registerRef="register.json",bindingsRef="bindings.json";

    ProductPromptRegisterFixture(Path root,ProductComparisonStartMtlsEngineeringSupport.Tls tls)throws Exception{
        this.root=root;Files.createDirectories(root);
        certificate=(X509Certificate)tls.load(tls.root.resolve("worker.p12")).getCertificate("worker");
        certificateSha=ProductCapturedArchive.digest(certificate.getEncoded());
        request.setSecure(true);request.setScheme("https");
        request.setAttribute("jakarta.servlet.request.X509Certificate",new X509Certificate[]{certificate});
        migrate(mainSource,"dm8/V032__product_identity.sql","dm8/V034__product_consultation_initial_task.sql",
                "dm8/V112__product_prompt_drafts.sql","dm8/V114__product_prompt_workspace_register.sql",
                "dm8/V115__product_prompt_body_clear.sql");
        migrate(authoritySource,"identity-authority/V001__grant_watermark.sql","identity-authority/V007__product_prompt_control.sql",
                "identity-authority/V011__product_prompt_workspace_register.sql","identity-authority/V012__product_prompt_body_clear.sql");
        db.update("INSERT INTO con3_organization VALUES(?,'Owned component organization','ACTIVE',1,CURRENT_TIMESTAMP)",organization);
        independent.update("INSERT INTO id2_maintainer VALUES(1,?,?,?,'GRANTS','ACTIVE',1)",subject,organization,certificateSha);
        var policy=businessBase();policy.put("format","PROMPT_DRAFT_RETENTION_POLICY/v1");policy.put("policyRef","policy.json");
        policy.put("bodyMaxDays",90);policy.put("auditMaxDays",180);policy.put("backupMaxDays",7);
        String policySha=write("policy.json",policy);
        var business=businessBase();business.put("format","PROMPT_WORKSPACE_APPROVAL/v1");business.put("decisionId",id());
        business.put("policyRef","policy.json");business.put("policySha",policySha);
        business.put("operations",List.of(ProductPromptWorkspaceEvidence.Action.values()));
        String businessSha=write("business.json",business);
        target=new ProductPromptRecords.Workspace(workspaceId,organization,1,"ACTIVE","policy.json",policySha,businessSha,now.plusSeconds(3600));
        var binding=new ProductPromptWorkspaceEvidence.Binding(workspaceId,organization,1,"business.json",businessSha,"policy.json",policySha);
        String bindingsSha=write(bindingsRef,Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.of(binding)));
        var files=new ProductSourceEvidenceFiles(root,1048576);
        var registry=new ProductPromptBindingRegistry(files,bindingsRef,bindingsSha);
        var registration=base();registration.put("format","PROMPT_WORKSPACE_REGISTER_APPROVAL/v1");registration.put("decisionId",id());
        registration.put("operation","PROMPT_WORKSPACE_REGISTER");registration.put("operatorSubject",subject);
        registration.put("operatorOrganization",organization);registration.put("certificateSha",certificateSha);registration.put("maintainerVersion",1);
        try(var main=mainSource.getConnection();var authority=authoritySource.getConnection()){
            registration.put("primaryDatabaseIdentitySha",ProductPromptRegistrationLease.databaseIdentitySha(main));
            registration.put("authorityDatabaseIdentitySha",ProductPromptRegistrationLease.databaseIdentitySha(authority));
        }
        registration.put("workspaceId",workspaceId);registration.put("organization",organization);registration.put("scopeVersion",1);
        registration.put("policyRef","policy.json");registration.put("policySha",policySha);registration.put("registrySha",businessSha);
        registration.put("workspaceValidUntil",target.validUntil());registration.put("receiptUntil",now.plusSeconds(3000));
        registration.put("traceCoverage","MINIMAL_OPERATION_SCALARS_AND_ORIGINAL_KEY");
        String registrationSha=write(registerRef,registration);
        approval=new ProductPromptRegisterApproval(files,registry,List.of(new ProductPromptRegisterApproval.Pin(workspaceId,registerRef,registrationSha)),clock);
        lease=newLease();requests=new ProductPromptRegisterStore(store,control,mapper,clock);
        reconciliation=new ProductPromptRegisterReconciliation(requests,clock);
        app=new ProductPromptRegisterApplication(approval,lease,requests,reconciliation);
        mainFault.clear();authorityFault.clear();
    }
    ProductPromptRegistrationLease newLease(){return new ProductPromptRegistrationLease(mainSource,authoritySource,mainManager,authorityManager,clock);}
    ProductPromptRegisterApplication reopenApplication(){var fresh=newLease();reopened.add(fresh);
        return new ProductPromptRegisterApplication(approval,fresh,requests,new ProductPromptRegisterReconciliation(requests,clock));}
    ProductPromptRegisterApproval.Witness witness(){return approval.admit(workspaceId);}
    void advance(Duration duration){now=now.plus(duration);}
    int count(Side side,String table){return (side==Side.MAIN?db:independent).queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    String state(){return independent.queryForObject("SELECT state FROM idp_workspace_register_request WHERE workspace_id=?",String.class,workspaceId);}
    List<Map<String,Object>> snapshot(Side side,String table){return (side==Side.MAIN?db:independent).queryForList("SELECT * FROM "+table);}
    void failCommit(Side side,int nth,boolean afterRealCommit){fault(side).armCommit(nth,afterRealCommit,null);}
    void failCommitForSql(Side side,String sqlFragment,boolean afterRealCommit){fault(side).armCommit(1,afterRealCommit,sqlFragment);}
    void failSql(Side side,Predicate<String> match){fault(side).failSql=match;}
    FaultSource fault(Side side){return side==Side.MAIN?mainFault:authorityFault;}
    @SuppressWarnings("unchecked") Map<String,Object> read(String ref)throws Exception{return mapper.readValue(Files.readAllBytes(root.resolve(ref)),TreeMap.class);}
    String write(String ref,Object value)throws Exception{byte[] bytes=mapper.writeValueAsBytes(value);Files.write(root.resolve(ref),bytes);return ProductCapturedArchive.digest(bytes);}
    Map<String,Object> base(){var value=new TreeMap<String,Object>();value.put("source","Owned component material, not business approval");
        value.put("decisionVersion","component-v1");value.put("responsibleParty","owned-component");value.put("decisionDate",LocalDate.ofInstant(now,ZoneOffset.UTC));
        value.put("status","ACTIVE");value.put("validFrom",now.minusSeconds(1));value.put("validUntil",now.plusSeconds(3600));return value;}
    private Map<String,Object> businessBase(){var value=base();value.put("workspaceId",workspaceId);value.put("organization",organization);
        value.put("scopeVersion",1);value.put("contentCoverage","PROJECT_AUTHORED_HUMAN_DRAFT");return value;}
    @Override public void close(){for(var owner:reopened)owner.close();lease.close();mainSource.close();authoritySource.close();}
    private static String id(){return UUID.randomUUID().toString();}
    private static HikariDataSource pool(FaultSource source,int capacity){
        var config=new HikariConfig();config.setDataSource(source);config.setMaximumPoolSize(capacity);config.setMinimumIdle(capacity);
        config.setConnectionTimeout(500);config.setValidationTimeout(250);config.setAllowPoolSuspension(false);
        config.setPoolName("register-component-"+id());return new HikariDataSource(config);
    }

    /** Faults wrap physical H2 connections underneath the exact production-facing Hikari source. */
    static final class FaultSource extends DelegatingDataSource {
        final List<String> sql=new CopyOnWriteArrayList<>(),commits=new CopyOnWriteArrayList<>();
        final List<String> connectionCalls=new CopyOnWriteArrayList<>();
        final List<String> foregroundConnectionCalls=new CopyOnWriteArrayList<>();
        final List<Integer> queryTimeouts=new CopyOnWriteArrayList<>(),networkTimeouts=new CopyOnWriteArrayList<>();
        final AtomicInteger pendingCommit=new AtomicInteger();
        final AtomicInteger pendingConnectionFailure=new AtomicInteger();
        volatile Predicate<String> failSql;
        volatile Consumer<String> afterSql;
        volatile String failConnectionMethod;
        volatile Thread connectionFailureThread;
        volatile Thread observedConnectionThread;
        volatile boolean failAfterCommit;
        volatile String commitSql;
        FaultSource(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:register-"+id()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000");setTargetDataSource(source);}
        @Override public Connection getConnection()throws SQLException{return wrap(super.getConnection());}
        @Override public Connection getConnection(String username,String password)throws SQLException{return wrap(super.getConnection(username,password));}
        void clear(){sql.clear();commits.clear();queryTimeouts.clear();networkTimeouts.clear();connectionCalls.clear();
            foregroundConnectionCalls.clear();observedConnectionThread=Thread.currentThread();
            failConnectionMethod=null;connectionFailureThread=null;pendingConnectionFailure.set(0);}
        void armConnection(String method,int nth){if(nth<1)throw new IllegalArgumentException("Positive occurrence required");
            connectionFailureThread=Thread.currentThread();pendingConnectionFailure.set(nth);failConnectionMethod=method;}
        void armCommit(int nth,boolean after,String fragment){commitSql=fragment;failAfterCommit=after;pendingCommit.set(nth);}
        private Connection wrap(Connection connection){
            var transactionSql=new ArrayList<String>();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                connectionCalls.add(method.getName());
                if(Thread.currentThread()==observedConnectionThread)foregroundConnectionCalls.add(method.getName());
                // A background Hikari connection adder must not consume a foreground cleanup fault.
                if(Thread.currentThread()==connectionFailureThread && method.getName().equals(failConnectionMethod)
                        &&(pendingConnectionFailure.get()==0||pendingConnectionFailure.decrementAndGet()==0)){
                    failConnectionMethod=null;throw new SQLException("Owned component connection "+method.getName()+" failure");
                }
                if(method.getName().equals("setNetworkTimeout"))networkTimeouts.add((Integer)args[1]);
                if(method.getName().equals("commit")){
                    boolean matching=commitSql==null||transactionSql.stream().anyMatch(value->value.contains(commitSql));
                    boolean fail=matching&&pendingCommit.get()>0&&pendingCommit.decrementAndGet()==0;
                    if(fail&&!failAfterCommit){commits.add("BEFORE_FAILURE");throw new SQLException("Owned component commit before failure");}
                    Object result=invoke(connection,method,args);commits.add("COMMITTED");transactionSql.clear();
                    if(fail){commits.add("AFTER_FAILURE");throw new SQLException("Owned component commit after failure");}return result;
                }
                if(method.getName().equals("rollback")){Object result=invoke(connection,method,args);transactionSql.clear();return result;}
                Object result=invoke(connection,method,args);
                if(result instanceof Statement statement){String prepared=args!=null&&args.length>0&&args[0] instanceof String?(String)args[0]:null;
                    Class<?> type=result instanceof java.sql.CallableStatement?java.sql.CallableStatement.class:
                            result instanceof java.sql.PreparedStatement?java.sql.PreparedStatement.class:Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(ignored,operation,parameters)->{
                        if(operation.getName().equals("setQueryTimeout"))queryTimeouts.add((Integer)parameters[0]);
                        String command=prepared!=null?prepared:parameters!=null&&parameters.length>0&&parameters[0] instanceof String?(String)parameters[0]:null;
                        boolean execute=operation.getName().startsWith("execute")&&command!=null;
                        if(execute){sql.add(command);transactionSql.add(command);var failure=failSql;
                            if(failure!=null&&failure.test(command)){failSql=null;throw new SQLException("Owned component SQL failure");}}
                        Object value=invoke(statement,operation,parameters);if(execute&&afterSql!=null)afterSql.accept(command);return value;
                    });
                }
                return result;
            });
        }
        private static Object invoke(Object target,Method method,Object[] args)throws Throwable{
            try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        }
    }
}
