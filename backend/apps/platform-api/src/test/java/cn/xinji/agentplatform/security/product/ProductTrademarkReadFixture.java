package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated generated capture only; no fixture identity or licence enters a product branch. */
final class ProductTrademarkReadFixture {
    static final String ISSUER="https://identity.example.org",AUDIENCE="https://api.example.org";
    final ProductTrademarkAuthorizationFixture origin;
    final JdbcTemplate db,authority;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final DataSourceTransactionManager manager;
    final TransactionTemplate transactions;
    final ProductGrantWatermark grants;
    final ProductTrademarkProjectionStore store;
    final ProductTrademarkReadTransaction boundary;
    final ProductTrademarkLibraryService service;
    final ProductTrademarkLibraryConfiguration.Assembly assembly;
    final JwtDecoder decoder;
    final ProductTaskDirectory.Task task;
    final String token;
    final Instant until;
    final Instant originalUntil;
    ProductTrademarkReadFixture(Path root) throws Exception {this(root,false);}
    ProductTrademarkReadFixture(Path root,boolean shortOriginal) throws Exception {
        this(root,shortOriginal,false);
    }
    ProductTrademarkReadFixture(Path root,boolean shortOriginal,boolean unicode) throws Exception {
        origin=new ProductTrademarkAuthorizationFixture(root,unicode);db=origin.db;authority=origin.authority;db.setQueryTimeout(5);
        origin.capture.clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);until=origin.capture.clock.instant().plusSeconds(3600);
        for(String path:List.of("V033__product_oauth2.sql","V038__product_task_source_scope.sql","V046__product_workload_subject.sql",
                "V057__product_oauth_client_control.sql","V008__local_public_knowledge_base_v1.sql","V113__product_trademark_projection.sql"))migrate("db/dm8/"+path);
        for(String purpose:List.of("retention","backup","recovery")){
            var policy=(ObjectNode)json.readTree(Files.readAllBytes(root.resolve(purpose+".json")));policy.put("until",until.toString());origin.file(purpose+".json",json.writeValueAsBytes(policy));
            origin.approval.put(purpose+"EvidenceSHA",origin.hash(purpose+".json"));
        }
        for(String key:List.of("validUntil","retainUntil","backupUntil","recoveryUntil"))origin.approval.put(key,until.toString());origin.seal();
        db.update("UPDATE con3_task SET expires_at=?,retention_evidence_sha=?",Timestamp.from(until),origin.hash("retention.json"));
        db.update("UPDATE con4_source_contract SET valid_until=?,retention_evidence_sha=?",Timestamp.from(until),origin.hash("retention.json"));
        db.update("UPDATE con4_source_admission SET valid_until=?,capability_sha=?",Timestamp.from(until),origin.pin.capabilitySHA());
        db.update("UPDATE con4_source_product_decision SET retention_evidence_sha=?",origin.hash("retention.json"));
        db.update("UPDATE con4_source_field_use SET valid_until=?",Timestamp.from(until));
        for(String field:List.of("NUMBER","NAME","APPLICANTS","ADDRESS","CLASSES","DATES","STATUS","SOURCE_REFERENCE"))for(String use:List.of("QUERY","DISPLAY"))
            db.update("INSERT INTO con4_source_field_use VALUES('decision',?,?,?)",field,use,Timestamp.from(until));
        origin.refreshHistory();
        manager=new DataSourceTransactionManager(origin.primary);transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,'reader','reader','encoded','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER);
        byte[] master=new byte[32];new java.security.SecureRandom().nextBytes(master);
        var keys=new ProductSigningKeys(db,transactions,ISSUER,Base64.getEncoder().encodeToString(master),Duration.ofMinutes(5),origin.capture.clock);String kid=keys.rotate("maintainer");
        db.update("INSERT INTO oauth2_registered_client(id,client_id,client_id_issued_at,client_name,client_authentication_methods,authorization_grant_types,redirect_uris,scopes,client_settings,token_settings) "
                +"VALUES('registered','public-client',CURRENT_TIMESTAMP,'Workbench','none','authorization_code','https://api.example.org/callback','openid','{}','{}')");
        db.update("INSERT INTO id2_client_current VALUES('public-client','registered','https://api.example.org/callback','ACTIVE',1,CURRENT_TIMESTAMP)");
        String sid=UUID.randomUUID().toString(),jti=UUID.randomUUID().toString();Instant now=origin.capture.clock.instant();
        token=new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer(ISSUER).subject("reader").audience(List.of(AUDIENCE))
                .issuedAt(now).notBefore(now.minusSeconds(1)).expiresAt(until).id(jti).claim("sid",sid).build())).getTokenValue();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)",sid,jti,ISSUER,"reader","registered",kid,Timestamp.from(now),Timestamp.from(until),"ACTIVE",null);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,ProductGrantHistoryBatch.digest(db,ISSUER,true,false));
        grants=new ProductGrantWatermark(db,authority,ISSUER);var issuer=new ProductGrants(db,transactions,origin.capture.clock);
        task=new JdbcProductTaskDirectory(db,origin.capture.clock).find("task").orElseThrow();
        grants.change(()->issuer.issue(new ProductGrants.Scope(ISSUER,"reader",task.organization(),"CONSULTATION",task.id(),task.dataScope(),"TASK",task.id(),task.authorizationVersion(),"READ","TASK_WORK"),until,0,"maintainer","read-grant","a".repeat(64)));
        var scopes=new ProductSourceScopeGrants(db,transactions,new JdbcProductTaskDirectory(db,origin.capture.clock),issuer,grants,origin.watermark,origin.maintenance,origin.capture.clock,ISSUER);
        scopes.authorizeVerified(new ProductSourceScopeGrants.Input("task","reader","source",1,1,until,"source-grant"),"maintainer","organization");
        db.update("INSERT INTO kb_source(source_id,source_name,entry_url,access_mode,authority_status,license_review_status,usage_decision,created_at) "
                +"VALUES('source','Isolated generated source','https://source.invalid','CONTROLLED','TEST_ONLY','TEST_ONLY','TEST_ONLY',CURRENT_TIMESTAMP)");
        store=new ProductTrademarkProjectionStore(db,json,origin.capture.clock);
        originalUntil=shortOriginal?now.plusSeconds(60):until;
        var context=new ProductTrademarkProjectionStore.Context("organization","task",7,"maintainer",origin.certSHA,"decision",1,1,"retention.json",1,origin.hash("retention.json"),origin.pin.approvedScopeSHA(),origin.pin.approvalEvidenceSHA(),originalUntil);
        transactions.execute(status->store.project(origin.input,context,"capture-key"));
        decoder=new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys,db,grants,ISSUER,AUDIENCE);
        var access=new ProductConsultationAccess(db,grants,ISSUER,origin.capture.clock);
        var captures=new ProductTrademarkReadCaptures(db,authority,origin.files,json,origin.capture.clock,List.of(origin.pin));
        boundary=new ProductTrademarkReadTransaction(db,manager,grants,access,decoder,ISSUER,origin.capture.clock,captures,store);
        service=new ProductTrademarkLibraryService(boundary,new ProductDm8TrademarkLibrarySource(boundary,new ProductTrademarkProjectionQuery(db,store,origin.capture.clock),origin.capture.clock),origin.capture.clock);
        assembly=new ProductTrademarkLibraryConfiguration.Assembly(boundary,service);
    }
    Query query(int page) {return new Query(null,null,null,Set.of(),Set.of(),null,null,null,null,new PageRequest(page,20,null),false);}
    ProductTrademarkReadBoundaryPort.Request request(Operation operation,RecordRef ref) {return new ProductTrademarkReadBoundaryPort.Request(token,"task",operation,ref,"request-1");}
    void sourceHistory(){origin.refreshHistory();}
    void grantHistory(){authority.update("UPDATE id2_authority_watermark SET history_sha=? WHERE issuer=?",ProductGrantHistoryBatch.digest(db,ISSUER,true,false),ISSUER);}
    private void migrate(String name) throws Exception {
        Path repo=Path.of("").toAbsolutePath();while(repo!=null&&!Files.exists(repo.resolve(name)))repo=repo.getParent();
        if(repo==null)throw new IllegalStateException("Migration missing");String sql=Files.readString(repo.resolve(name)).replace("MODIFY mark_name NULL","ALTER COLUMN mark_name DROP NOT NULL");
        try(var connection=origin.primary.getConnection()){ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}
    }
}
