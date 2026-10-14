package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable owned environment. Every identity/grant is registered by actual product endpoints. */
final class ProductComparisonRealSecurityEnvironment implements AutoCloseable {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    final Path root=Files.createTempDirectory("comparison-real-security-");
    ConfigurableApplicationContext app;
    private final Map<String,Object> properties=new LinkedHashMap<>();
    private final boolean managedStartMode,preserveOnClose;
    final Instant retentionUntil;
    private boolean closed;
    final String origin,issuer,organization="owned-engineering-security";
    final HttpClient maintainer,browser;
    final JdbcTemplate primary,authority;
    final Instant until;
    final String password=UUID.randomUUID()+"Aa!";
    /** Legacy composition and disposal are unchanged. */
    ProductComparisonRealSecurityEnvironment() throws Exception { this(null); }
    /** Optional Start composition under this task's fixed approval; true retains only private DB/Source bytes. */
    ProductComparisonRealSecurityEnvironment(boolean preserve) throws Exception {
        this(new StartOptions(Instant.parse("2026-10-08T10:00:00Z"),Duration.ofHours(1),preserve));
    }

    /** Explicit, bounded approval; this does not grant any product permission. */
    record StartOptions(Instant approvedUntil,Duration engineeringRetention,boolean preserveOnClose) {
        StartOptions {
            Objects.requireNonNull(approvedUntil);Objects.requireNonNull(engineeringRetention);
            if(engineeringRetention.isZero()||engineeringRetention.isNegative()
                    ||engineeringRetention.compareTo(Duration.ofHours(1))>0)
                throw new IllegalArgumentException("Engineering retention must be positive and at most PT1H");
            Instant ceiling=Instant.parse("2026-10-08T10:00:00Z"); // User approval: 18:00 Asia/Shanghai.
            if(approvedUntil.isAfter(ceiling)||!approvedUntil.isAfter(Instant.now()))
                throw new IllegalArgumentException("Approval must be current and not later than 2026-10-08 18:00 +08:00");
        }
    }
    ProductComparisonRealSecurityEnvironment(StartOptions options) throws Exception {
        Instant created=Instant.now();
        managedStartMode=options!=null;preserveOnClose=options!=null&&options.preserveOnClose();
        retentionUntil=(options==null?created.plusSeconds(3600):
                earlier(options.approvedUntil(),created.plus(options.engineeringRetention())))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        until=earlier(created.plusSeconds(1800),retentionUntil).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(managedStartMode)privateDirectory(root);
        tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();
        try {
        if(managedStartMode)privateDirectory(tls.root);
        tls.leaf("maintainer","CN=Owned Engineering Security Maintainer","clientAuth",null);
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        origin="https://localhost:"+port;issuer=origin;
        String main="jdbc:h2:file:"+root.resolve("primary").toString().replace('\\','/');
        String auth="jdbc:h2:file:"+root.resolve("authority").toString().replace('\\','/');
        primary=db(main);authority=db(auth);
        // Reuse the approved physical schema list, not the old test's authorization fixtures or time.
        String locations="file:db/dm8/V032__product_identity.sql,file:db/dm8/V033__product_oauth2.sql,file:db/dm8/V057__product_oauth_client_control.sql,file:db/dm8/V034__product_consultation_initial_task.sql,file:db/dm8/V036__product_source_candidates.sql,file:db/dm8/V088__product_source_capture_identity.sql,file:db/dm8/V037__product_source_admission.sql,file:db/dm8/V038__product_task_source_scope.sql,file:db/dm8/V039__product_source_verification.sql,file:db/dm8/V041__product_task_authorization_version.sql,file:db/dm8/V042__product_consultation_goal.sql,file:db/dm8/V043__product_task_cancel_epoch.sql,file:db/dm8/V044__product_task_session_resume.sql,file:db/dm8/V058__product_task_organization_create.sql,file:db/dm8/V045__product_task_execution_fence.sql,file:db/dm8/V048__product_task_execution_reconciliation.sql,file:db/dm8/V077__product_execution_attempt_history.sql,file:db/dm8/V072__product_search_metadata_filters.sql,file:db/dm8/V046__product_workload_subject.sql,file:db/dm8/V047__product_task_worker_delegation.sql,file:db/dm8/V053__product_source_use_decision.sql,file:db/dm8/V056__product_query_only_source_scope.sql,file:db/dm8/V061__product_worker_execution_request.sql,file:db/dm8/V040__product_source_results.sql,file:db/dm8/V063__product_search_execution_binding.sql,file:db/dm8/V064__product_search_ranking_evidence.sql,file:db/dm8/V065__product_search_stable_cursor.sql,file:db/dm8/V076__product_search_selection.sql,file:db/dm8/V078__product_search_attempt_history_binding.sql";
        for(String file:locations.split(","))script(primary,file.substring(5));
        script(primary,"db/dm8/V092__product_source_comparison_use.sql");
        for(String name:List.of("V094__product_comparison_feature_confirmation.sql","V095__product_comparison_input.sql","V098__product_comparison_worker_delegation.sql","V099__product_comparison_candidate_lease.sql","V101__product_comparison_prepare_request.sql"))script(primary,"db/dm8/"+name);
        if(managedStartMode)for(String name:List.of("V100__product_comparison_matrix.sql",
                "V102__product_comparison_start_command.sql","V103__comparison_start_fence_presence.sql",
                "V104__product_title_result_build_provenance.sql","V105__comparison_start_handoff_receipt.sql"))
            script(primary,"db/dm8/"+name);
        if(managedStartMode){script(primary,"db/dm8/V106__product_display_only_source_scope.sql");
            script(primary,"db/dm8/V089__product_consultation_model_turn.sql");
            script(primary,"db/dm8/V097__consultation_model_invocation.sql");}
        script(authority,"db/identity-authority/V001__grant_watermark.sql");
        script(authority,"db/identity-authority/V003__consultation_source_watermark.sql");
        authority.execute("CREATE USER ENG_SERVICE PASSWORD ''");
        authority.execute("CREATE USER ENG_BACKUP PASSWORD ''");
        var peer=certificate(tls.root.resolve("maintainer.cer"));
        var manifest=new ProductIdentityBootstrap.Manifest(issuer,"owned-engineering-maintainer",organization,
                tls.root.resolve("maintainer.cer").toString(),tls.root.resolve("ca.cer").toString(),
                peer.getSubjectX500Principal().getName(),sha(peer.getEncoded()),List.of("SUBJECTS","GRANTS","KEYS","SOURCES"));
        ProductIdentityBootstrap.bootstrap(primary,authority,new TransactionTemplate(new DataSourceTransactionManager(authority.getDataSource())),
                manifest,sha(certificate(tls.root.resolve("ca.cer")).getEncoded()),"ENG_SERVICE","ENG_BACKUP");
        authority.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON id4_source_watermark TO ENG_SERVICE");

        var props=properties;
        props.put("server.port",port);props.put("server.address","127.0.0.1");
        props.put("server.ssl.enabled",true);props.put("server.ssl.key-store",tls.root.resolve("server.p12").toUri());
        props.put("server.ssl.key-store-password",tls.password);props.put("server.ssl.key-store-type","PKCS12");
        props.put("server.ssl.trust-store",tls.root.resolve("trust.p12").toUri());
        props.put("server.ssl.trust-store-password",tls.password);props.put("server.ssl.client-auth","want");
        if(managedStartMode){
            props.put("server.tomcat.accesslog.enabled",true);
            props.put("server.tomcat.accesslog.directory",root.resolve("access-log").toString());
            props.put("server.tomcat.accesslog.pattern","%m %U %s %D");
            props.put("server.ssl.enabled-protocols","TLSv1.2");
        }
        props.put("spring.datasource.url",main);props.put("spring.datasource.username","sa");
        props.put("spring.datasource.driver-class-name","org.h2.Driver");props.put("spring.sql.init.mode","never");
        props.put("product.identity.enabled",true);props.put("product.identity.issuer",issuer);
        props.put("product.identity.audience",origin);props.put("product.identity.master-key",Base64.getEncoder().encodeToString(random(32)));
        props.put("product.identity.token-verification-window","PT5M");props.put("product.identity.activation-lifetime","PT30M");
        props.put("product.identity.minimum-password-length",12);
        props.put("product.identity.authority.jdbc-url",auth);props.put("product.identity.authority.schema","PUBLIC");
        props.put("product.identity.authority.username","ENG_SERVICE");props.put("product.identity.authority.password","");
        props.put("product.source.engine.enabled",true);props.put("product.source.evidence.root",root.toString());
        props.put("product.source.evidence.max-bytes",1000000);props.put("product.source.results.max-results",100);
        props.put("product.source.results.cursor.hmac-key-base64",Base64.getEncoder().encodeToString(random(32)));
        if(managedStartMode){
            props.put("spring.profiles.active","comparison-real-security");
            props.put("spring.datasource.hikari.schema","PUBLIC");
            props.put("product.consultation.comparison.enabled",true);
            props.put("product.consultation.comparison.start.enabled",false);
            props.put("product.consultation.comparison.calculation.enabled",false);
            props.put("product.consultation.model.enabled",false);
        }
        String contract=managedStartMode
                ? "Owned project-authored originals; isolated DISPLAY/COMPARISON engineering only; zero models; "
                    +"approval and actual engineering retention end at "+retentionUntil+"; max age "+options.engineeringRetention()+"."
                : "Owned isolated engineering metadata; no third-party records, model disclosure or business task; max age PT1H.";
        Files.writeString(root.resolve("engineering-retention.txt"),contract);
        for(String prefix:List.of("product.consultation.retention","product.consultation.goal-retention",
                "product.source.registry.retention","product.source.results.retention")){
            props.put(prefix+".ref","owned-engineering-retention");props.put(prefix+".version",1);
            props.put(prefix+".evidence-sha256",sha(contract.getBytes(StandardCharsets.UTF_8)));
            props.put(prefix+".max-age",managedStartMode?options.engineeringRetention().toString():"PT1H");
        }
        props.put("product.consultation.goal-retention.max-bytes",16384);
        if(managedStartMode)props.put("product.consultation.workspace.enabled",true);
        app=launch();
        maintainer=HttpClient.newBuilder().sslContext(tls.context("maintainer",true)).build();
        browser=HttpClient.newBuilder().sslContext(tls.context(null,true)).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        }catch(Exception|Error failure){
            try{if(app!=null)app.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try{tls.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try{ProductComparisonStartMtlsEngineeringSupport.erase(root);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            properties.clear();throw failure;
        }
    }
    private ConfigurableApplicationContext launch(){
        requireCurrent();
        if(!managedStartMode)return new SpringApplicationBuilder(ProductComparisonRealSecurityConfiguration.class)
                .profiles("comparison-real-security").properties(properties).run("--spring.profiles.active=comparison-real-security");
        // Command-line values win over ambient application properties. Values stay in memory; never log/export them.
        String[] args=properties.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(managedStartMode?ProductComparisonRealSecurityStartConfiguration.class:
                ProductComparisonRealSecurityConfiguration.class).profiles("comparison-real-security").run(args);
    }
    /** Pass subjectId from POST /v2/identity/workloads using this environment's worker certificate. */
    synchronized void configureStart(String registeredWorkerSubject) throws Exception {
        if(!managedStartMode)throw new IllegalStateException("Managed Start composition was not selected");
        requireCurrent();
        if(registeredWorkerSubject==null||registeredWorkerSubject.isBlank())throw new IllegalArgumentException("Actual worker subject required");
        String fingerprint=sha(certificate(tls.root.resolve("worker.cer")).getEncoded());
        Integer count=primary.queryForObject("SELECT COUNT(*) FROM id2_workload w JOIN id2_subject s "
                +"ON s.issuer=w.issuer AND s.subject_id=w.subject_id WHERE w.issuer=? AND w.subject_id=? "
                +"AND w.organization=? AND w.certificate_sha=? AND w.workload_kind='AGENT' "
                +"AND w.status='ACTIVE' AND s.status='ACTIVE' AND w.expires_at>=?",Integer.class,
                issuer,registeredWorkerSubject,organization,fingerprint,java.sql.Timestamp.from(until));
        if(count==null||count!=1)throw new IllegalArgumentException("Current registered worker certificate/subject binding required");
        String prefix="product.consultation.comparison.start.";
        properties.put(prefix+"enabled",true);
        properties.put(prefix+"worker-subject",registeredWorkerSubject);
        properties.put(prefix+"configuration-version","VER-CON-USER-START-MTLS-01");
        properties.put(prefix+"lease-millis",60000);
        properties.put(prefix+"product-origin",origin);
        properties.put(prefix+"worker-keystore",tls.root.resolve("worker.p12").toString());
        properties.put(prefix+"worker-password",tls.password);
        properties.put(prefix+"truststore",tls.root.resolve("trust.p12").toString());
        properties.put(prefix+"truststore-password",tls.password);
    }
    /** Convenience entry after actual HTTP registration; enables handoff and restarts, never computation. */
    synchronized void enableStart(String registeredWorkerSubject) throws Exception {
        configureStart(registeredWorkerSubject);restart();
    }
    /** Serve the actual compiled workface in the owned HTTPS process, never a test product page. */
    synchronized void serveWorkbench(Path built) throws Exception {
        requireCurrent();if(!Files.isRegularFile(built.resolve("consultation.html")))throw new IllegalArgumentException("Compiled product workface required");
        Path publicRoot=root.resolve("public"),appRoot=publicRoot.resolve("app");
        try(var paths=Files.walk(built)){for(Path from:paths.toList()){
            Path to=appRoot.resolve(built.relativize(from));
            if(Files.isDirectory(from))Files.createDirectories(to);else Files.copy(from,to);
        }}
        properties.put("spring.web.resources.static-locations",publicRoot.toUri().toString());
        restart();
    }
    /** Same issuer, port, file databases, crypto keys and mTLS material; no migration/bootstrap replay. */
    synchronized void restart(){requireCurrent();stop();app=launch();}
    synchronized void stop(){if(app!=null){app.close();app=null;}}
    private void requireCurrent(){
        if(closed||managedStartMode&&!Instant.now().isBefore(until))
            throw new IllegalStateException("Environment closed or approved engineering window expired");
    }
    private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static void privateDirectory(Path directory) throws Exception {
        var acl=Files.getFileAttributeView(directory,AclFileAttributeView.class);
        if(acl!=null){
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
        }else Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
    }
    JsonNode post(HttpClient client,String path,Object body,String key,String token,int expected) throws Exception {
        var response=send(client,"POST",path,json.writeValueAsString(body),key,token,"application/json");
        if(response.statusCode()!=expected)throw new AssertionError(path+": expected "+expected+" got "+response.statusCode()+" "+response.body());
        if(response.body().isBlank())return json.createObjectNode();
        try{return json.readTree(response.body());}catch(com.fasterxml.jackson.core.JsonProcessingException text){return json.getNodeFactory().textNode(response.body());}
    }
    HttpResponse<String> send(HttpClient client,String method,String path,String body,String key,String token,String type) throws Exception {
        if(managedStartMode)requireCurrent();
        var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15));
        if(key!=null)request.header("Idempotency-Key",key);if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type",type);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String login(String login,String secret) throws Exception {
        String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(random(48));
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var page=send(browser,"GET","/login",null,null,null,null);
        var csrf=java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        if(!csrf.find())throw new AssertionError("Actual login CSRF missing");
        var logged=send(browser,"POST","/login","username="+enc(login)+"&password="+enc(secret)+"&_csrf="+enc(csrf.group(1)),null,null,"application/x-www-form-urlencoded");
        if(logged.statusCode()!=302)throw new AssertionError("Actual login rejected "+logged.statusCode());
        String authorize="/oauth2/authorize?response_type=code&client_id=owned-security-client&scope=openid&redirect_uri="+enc(origin+"/callback")+"&state=owned&code_challenge_method=S256&code_challenge="+challenge;
        var code=send(browser,"GET",authorize,null,null,null,null);
        String location=code.headers().firstValue("location").orElseThrow();
        if(!location.contains("code="))throw new AssertionError("OAuth authorization did not issue code: "+location);
        String value=URLDecoder.decode(location.split("code=",2)[1].split("&")[0],StandardCharsets.UTF_8);
        var token=send(browser,"POST","/oauth2/token","grant_type=authorization_code&client_id=owned-security-client&redirect_uri="+enc(origin+"/callback")+"&code="+enc(value)+"&code_verifier="+verifier,null,null,"application/x-www-form-urlencoded");
        if(token.statusCode()!=200)throw new AssertionError("Real token endpoint failed: "+token.statusCode()+" "+token.body());
        return json.readTree(token.body()).path("access_token").asText();
    }
    static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static byte[] random(int size){byte[] bytes=new byte[size];new SecureRandom().nextBytes(bytes);return bytes;}
    static JdbcTemplate db(String url){var ds=new JdbcDataSource();ds.setURL(url);ds.setUser("sa");return new JdbcTemplate(ds);}
    static void script(JdbcTemplate db,String file)throws Exception{try(var c=db.getDataSource().getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(file));}}
    static X509Certificate certificate(Path file)throws Exception{try(var input=Files.newInputStream(file)){return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);}}
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    /** Preserve only private DB/Source bytes when explicitly selected; never preserve TLS credentials.
     * No background executor: the owner records retentionUntil and is responsible for timely purge.
     */
    @Override public synchronized void close() throws Exception {
        if(closed)return;
        closed=true;
        try{stop();}finally{
            try{tls.close();}finally{
                properties.clear();
                if(!preserveOnClose||!Instant.now().isBefore(retentionUntil)){
                    if(Files.exists(root))ProductComparisonStartMtlsEngineeringSupport.erase(root);
                }
            }
        }
    }
    /** Explicit owner cleanup. Preserved private bytes must be purged no later than retentionUntil. */
    synchronized void purge() throws Exception {
        try{close();}finally{
            if(Files.exists(root))ProductComparisonStartMtlsEngineeringSupport.erase(root);
        }
    }
}









