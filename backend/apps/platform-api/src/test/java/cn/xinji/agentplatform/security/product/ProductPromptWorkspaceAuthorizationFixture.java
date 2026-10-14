package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual SQL/crypto component inputs, not admitted runtime workspaces or approvals. */
final class ProductPromptWorkspaceAuthorizationFixture {
    static final String ISSUER="https://identity.example.org",AUDIENCE="https://api.example.org";
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}};
    final JdbcDataSource mainSource=source(),authoritySource=source();
    final AtomicInteger bodyReads=new AtomicInteger();
    final JdbcTemplate db=new JdbcTemplate(mainSource){
        @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){
            if(sql.startsWith("SELECT revision_json"))bodyReads.incrementAndGet();return super.query(sql,mapper,args);
        }
    };
    final JdbcTemplate independent=new JdbcTemplate(authoritySource);
    final DataSourceTransactionManager mainManager=new DataSourceTransactionManager(mainSource),authorityManager=new DataSourceTransactionManager(authoritySource);
    final TransactionTemplate mainTx=new TransactionTemplate(mainManager),authorityTx=new TransactionTemplate(authorityManager);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final String organization=id(),workspaceId=id(),sid=id();
    final Path root;
    final Map<String,Binding> bindings=new TreeMap<>();
    final Map<String,ProductGrants.Receipt> receipts=new TreeMap<>();
    final ProductPromptStore store;
    final ProductPromptAuthorityStore control;
    final ProductSigningKeys keys;
    final String masterKeyBase64;
    final ProductGrantWatermark watermark;
    final ProductConsultationAccess access;
    final ProductGrants grants;
    final JwtDecoder decoder;
    final String token;

    ProductPromptWorkspaceAuthorizationFixture(Path root)throws Exception{
        this.root=root;
        migrate(mainSource,"dm8/V032__product_identity.sql","dm8/V033__product_oauth2.sql",
                "dm8/V034__product_consultation_initial_task.sql","dm8/V046__product_workload_subject.sql",
                "dm8/V057__product_oauth_client_control.sql","dm8/V112__product_prompt_drafts.sql","dm8/V115__product_prompt_body_clear.sql");
        migrate(authoritySource,"identity-authority/V001__grant_watermark.sql","identity-authority/V007__product_prompt_control.sql",
                "identity-authority/V012__product_prompt_body_clear.sql");
        independent.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,ProductComparisonJson.hash(""));
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                +"VALUES(?,'member','member','encoded','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER);
        byte[] master=new byte[32];new SecureRandom().nextBytes(master);
        masterKeyBase64=Base64.getEncoder().encodeToString(master);
        keys=new ProductSigningKeys(db,mainTx,ISSUER,masterKeyBase64,Duration.ofMinutes(5),clock);
        String kid=keys.rotate("fixture-maintainer");java.util.Arrays.fill(master,(byte)0);
        db.update("INSERT INTO oauth2_registered_client(id,client_id,client_id_issued_at,client_name,client_authentication_methods,"
                +"authorization_grant_types,redirect_uris,scopes,client_settings,token_settings) "
                +"VALUES('registered','public-client',CURRENT_TIMESTAMP,'Workbench','none','authorization_code',"
                +"'https://api.example.org/callback','openid','{}','{}')");
        db.update("INSERT INTO id2_client_current VALUES('public-client','registered','https://api.example.org/callback','ACTIVE',1,CURRENT_TIMESTAMP)");
        token=token(kid,ISSUER,AUDIENCE);
        watermark=new ProductGrantWatermark(db,independent,ISSUER);
        access=new ProductConsultationAccess(db,watermark,ISSUER,clock);grants=new ProductGrants(db,mainTx,clock);
        decoder=new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys,db,watermark,ISSUER,AUDIENCE);
        store=new ProductPromptStore(db,mainManager,mapper,clock);control=new ProductPromptAuthorityStore(independent,authorityManager,clock);
        addWorkspace(workspaceId,organization);
        db.execute("CREATE TABLE prompt_admission_observation(message VARCHAR(80))");
    }
    String token(String kid,String issuer,String audience){
        String jti=id();
        String value=new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer(issuer).subject("member")
                .audience(List.of(audience)).issuedAt(now).notBefore(now.minusSeconds(1)).expiresAt(now.plusSeconds(3600))
                .id(jti).claim("sid",sid).build())).getTokenValue();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)",sid,jti,ISSUER,"member","registered",kid,
                Timestamp.from(now),Timestamp.from(now.plusSeconds(3600)),"ACTIVE",null);return value;
    }
    void addWorkspace(String id,String org)throws Exception{
        if(db.queryForObject("SELECT COUNT(*) FROM con3_organization WHERE organization_id=?",Long.class,org)==0)
            db.update("INSERT INTO con3_organization VALUES(?,'Component organization','ACTIVE',1,CURRENT_TIMESTAMP)",org);
        String policyRef="policy-"+id+".json",approvalRef="approval-"+id+".json";
        var policy=base(id,org);policy.put("format","PROMPT_DRAFT_RETENTION_POLICY/v1");policy.put("policyRef",policyRef);
        policy.put("bodyMaxDays",90);policy.put("auditMaxDays",180);policy.put("backupMaxDays",7);
        String policySha=write(policyRef,policy);
        var approval=base(id,org);approval.put("format","PROMPT_WORKSPACE_APPROVAL/v1");approval.put("decisionId",ProductPromptWorkspaceAuthorizationFixture.id());
        approval.put("policyRef",policyRef);approval.put("policySha",policySha);approval.put("operations",List.of(Action.values()));
        String approvalSha=write(approvalRef,approval);
        Binding binding=new Binding(id,org,1,approvalRef,approvalSha,policyRef,policySha);bindings.put(id,binding);
        Workspace workspace=new Workspace(id,org,1,"ACTIVE",policyRef,policySha,approvalSha,now.plusSeconds(1800));
        String intent=ProductPromptWorkspaceAuthorizationFixture.id();
        authorityTx.executeWithoutResult(status->control.insertWorkspacePending(workspace,intent,now.plusSeconds(600)));
        mainTx.executeWithoutResult(status->store.insertWorkspace(workspace));authorityTx.executeWithoutResult(status->control.completeWorkspace(workspace,intent));
    }
    Map<String,Object> base(String id,String org){
        var values=new TreeMap<String,Object>();values.put("source","Owned component approval input only");values.put("decisionVersion","component-v1");
        values.put("responsibleParty","owned-component");values.put("decisionDate",LocalDate.now(ZoneOffset.UTC));values.put("status","ACTIVE");
        values.put("validFrom",now.minusSeconds(1));values.put("validUntil",now.plusSeconds(3600));values.put("workspaceId",id);
        values.put("organization",org);values.put("scopeVersion",1);values.put("contentCoverage","PROJECT_AUTHORED_HUMAN_DRAFT");return values;
    }
    void grant(Action action){grant(workspaceId,action);}
    void grant(String id,Action action){
        Binding binding=bindings.get(id);String scope="PROMPT_WORKSPACE:"+id+":1";
        String code=switch(action){case CREATE,COPY->"CREATE";case SAVE->"WRITE";case READ,PREVIEW->"READ";};
        var receipt=watermark.change(()->grants.issue(new ProductGrants.Scope(ISSUER,"member",binding.organization(),"CONSULTATION",scope,scope,
                "PROMPT_WORKSPACE",id+":"+action.name(),1,code,"TASK_WORK"),now.plusSeconds(1700),0,"component-maintainer",id(),ProductComparisonJson.hash(id())));
        receipts.put(id+":"+action.name(),receipt);
    }
    void taskOnlyGrant(){
        watermark.change(()->grants.issue(new ProductGrants.Scope(ISSUER,"member",organization,"CONSULTATION","task","scope","TASK","task",1,"READ","TASK_WORK"),
                now.plusSeconds(1700),0,"component-maintainer",id(),ProductComparisonJson.hash(id())));
    }
    void revoke(Action action){var receipt=receipts.get(workspaceId+":"+action.name());
        watermark.change(()->grants.revoke(receipt.grantId(),1,"component-maintainer",id(),ProductComparisonJson.hash(id())));}
    ProductPromptWorkspaceAuthorization authorization(){return authorization(List.copyOf(bindings.values()));}
    ProductPromptWorkspaceAuthorization authorization(List<Binding> selected){
        return new ProductPromptWorkspaceAuthorization(store,control,mainManager,
                new ProductPromptWorkspaceEvidence(selected,new ProductSourceEvidenceFiles(root,1048576)),watermark,access,decoder,ISSUER,clock);
    }
    void alterApproval(Consumer<Map<String,Object>> change)throws Exception{
        Binding old=bindings.get(workspaceId);var value=read(old.approvalRef());change.accept(value);installApproval(mapper.writeValueAsString(value));
    }
    void installApproval(String raw)throws Exception{
        Binding old=bindings.get(workspaceId);Files.writeString(root.resolve(old.approvalRef()),raw,StandardCharsets.UTF_8);
        String sha=ProductComparisonJson.hash(raw);Binding next=new Binding(old.workspaceId(),old.organization(),old.scopeVersion(),old.approvalRef(),sha,old.policyRef(),old.policySha());
        bindings.put(workspaceId,next);db.update("UPDATE conp_workspace SET registry_sha=? WHERE workspace_id=?",sha,workspaceId);
        independent.update("UPDATE idp_workspace SET registry_sha=?,expected_registry_sha=? WHERE workspace_id=?",sha,sha,workspaceId);
    }
    void alterPolicy(Consumer<Map<String,Object>> change)throws Exception{
        Binding old=bindings.get(workspaceId);var policy=read(old.policyRef());change.accept(policy);String sha=write(old.policyRef(),policy);
        bindings.put(workspaceId,new Binding(old.workspaceId(),old.organization(),old.scopeVersion(),old.approvalRef(),old.approvalSha(),old.policyRef(),sha));
        db.update("UPDATE conp_workspace SET policy_sha=? WHERE workspace_id=?",sha,workspaceId);
        independent.update("UPDATE idp_workspace SET policy_sha=? WHERE workspace_id=?",sha,workspaceId);
        alterApproval(value->value.put("policySha",sha));
    }
    void writeObservation(){db.update("INSERT INTO prompt_admission_observation VALUES('guarded callback')");}
    int observations(){return db.queryForObject("SELECT COUNT(*) FROM prompt_admission_observation",Integer.class);}
    void bodyAttempt(){db.query("SELECT revision_json FROM conp_revision",(rs,n)->rs.getString(1));}
    @SuppressWarnings("unchecked")
    Map<String,Object> read(String reference)throws Exception{return mapper.readValue(Files.readAllBytes(root.resolve(reference)),TreeMap.class);}
    String write(String reference,Object value)throws Exception{
        String raw=mapper.writeValueAsString(value);Files.writeString(root.resolve(reference),raw,StandardCharsets.UTF_8);return ProductComparisonJson.hash(raw);
    }
    static String id(){return UUID.randomUUID().toString();}
    static JdbcDataSource source(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:prompt-admission-"+id()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");return source;}
}
