package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;
import cn.xinji.agentplatform.enforcement.legalreference.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Controlled upstream test-input preparation ONLY. Not ENF17 acceptance, legal advice, or business approval. */
class EnforcementPenaltyPositiveInputIT {
    @Test void prepareExplicitSymbolicInputAndExactCurrentGrants() throws Exception {
        var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authority=new DriverManagerDataSource(req("ENF_ACCEPTANCE_AUTHORITY_URL"),req("ENF_ACCEPTANCE_AUTHORITY_USER"),req("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {var c=super.getConnection();try{c.setSchema("ENFACC_AUTH");return c;}catch(java.sql.SQLException e){c.close();throw e;}}
        };
        assertTrue(data.getUrl().startsWith("jdbc:dm://"));assertNotEquals(data.getUrl(),authority.getUrl());
        var db=new JdbcTemplate(data);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        var json=new ObjectMapper().findAndRegisterModules();
        String run=req("ENF_PENALTY_RUN_ID"),org=req("ENF_ACCEPTANCE_ORGANIZATION"),subject=req("ENF_ACCEPTANCE_SUBJECT"),issuer=req("ENF_ACCEPTANCE_ISSUER");
        String id="ENF15-POSITIVE-"+run,refId=UUID.nameUUIDFromBytes(("ENF15-TEST-BASIS-"+run).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        long revision=Long.parseLong(req("ENF_PENALTY_GRANT_REVISION"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND status='ACTIVE'",Integer.class,issuer,subject));
        var legal=new LegalReferenceJdbcStore(db,tx,json,Clock.systemUTC());
        var directory=Path.of(req("ENF_PENALTY_POSITIVE_INPUT_ROOT"));
        if("true".equals(System.getenv("ENF_PENALTY_SEED_INPUT"))) {
            assertEquals(1,revision);
            assertFalse(Files.exists(directory),"New controlled input root required");Files.createDirectories(directory);
            String text="PROJECT_AUTHORED_FICTIONAL / FUNCTIONAL_TEST_INPUT ONLY. The technical score is B * 2, inclusive range 0..100 POINT, scale 2 HALF_UP. This is not a statute, penalty amount, legal applicability finding, official authorization, or business acceptance.";
            Path original=directory.resolve("symbolic-input.txt");Files.writeString(original,text);
            var candidate=new Candidate("PROJECT_SCORE_ARTICLE_1","PROJECT_SCORE_INPUT_"+run,1,"2026-10-01","SYMBOLIC_INPUT_1","[TEST INPUT ONLY] Project technical score definition","symbolic-input.txt",text,LegalReferenceService.hash(text),"VERIFIED","CLEAR","VERIFIED means this explicitly simulated test definition matches its retained input; no real legal conclusion.",List.of(),"REVIEW_REQUIRED");
            var provenance=new FileLegalKnowledgeSource.Provenance("PROJECT_AUTHORED_FICTIONAL","urn:project:functional-test:"+run,original.toAbsolutePath().toString(),LegalReferenceService.hash(text),Instant.now().toString(),"FUNCTIONAL_TEST_INPUT_ONLY_NOT_BUSINESS_ACCEPTANCE","SIMULATED_NOT_STATUTORY_NOT_FOR_PRODUCTION");
            var catalog=new FileLegalKnowledgeSource.Catalog(List.of(new FileLegalKnowledgeSource.Entry(candidate.sourceId(),1,candidate.sourceVersion(),"AVAILABLE",null,List.of(candidate))),provenance);
            Path catalogFile=directory.resolve("catalog.json");json.writeValue(catalogFile.toFile(),catalog);
            assertEquals(candidate,new FileLegalKnowledgeSource(catalogFile,json).exact(candidate.sourceId(),candidate.sourceVersion(),candidate.candidateId()));
            var existing=db.query("SELECT payload FROM enf9_legal_reference WHERE organization_id=? AND source_id='CNIPA_PATENT_IMPLEMENTING_RULES_2023' ORDER BY created_at DESC",(r,n)->{try{return json.readValue(r.getString(1),ReferenceRevision.class);}catch(Exception e){throw new IllegalStateException(e);}},org);
            assertFalse(existing.isEmpty(),"Existing target required; no invented target");var target=existing.getFirst();
            String digest=new LegalReferenceTargetStore(db,json).require(subject,org,target.targetType(),target.targetId(),target.targetRevision(),target.targetAnchor(),"LEGAL_BASIS");
            assertEquals(0,legal.latestReferenceVersion(refId));
            var reference=new ReferenceRevision(refId,1,org,UUID.nameUUIDFromBytes(("INPUT-"+run).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),1,candidate.candidateId(),candidate.sourceId(),1,candidate.sourceVersion(),candidate.articleId(),candidate.officialTitle(),candidate.locator(),text,candidate.verbatimSha256(),candidate.explanation(),candidate.effectStatus(),candidate.transitionStatus(),List.of(),target.targetType(),target.targetId(),target.targetRevision(),target.targetAnchor(),digest,ReviewState.CONFIRMED,BindingState.ACTIVE,"CONTROLLED_TEST_INPUT_NOT_REAL_APPROVER","Explicit simulated upstream input; does not claim official review or statutory validity",0);
            var search=new SearchResult(reference.searchId(),1,SearchState.COMPLETED,org,target.targetType(),target.targetId(),target.targetRevision(),target.targetAnchor(),digest,"CONTROLLED-TEST-CASE","LEGAL_BASIS","PROJECT_TEST",Instant.now(),"symbolic",List.of(candidate.sourceId()),List.of(),List.of(candidate));
            legal.command("controlled-input-"+run,LegalReferenceService.hash(reference.toString()),"CONTROLLED_TEST_INPUT","IMPORT_CONTROLLED_TEST_INPUT",()->{legal.addSearch(search);legal.addReference(reference);return new Receipt("controlled-input-"+run,"IMPORT_CONTROLLED_TEST_INPUT",refId,1);});
        }
        var reference=legal.reference(refId,1).orElseThrow();
        assertEquals("PROJECT_SCORE_INPUT_"+run,reference.sourceId());
        var grants=new ProductGrants(db,tx,Clock.systemUTC());var watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),issuer);
        String previous=System.getenv("ENF_PENALTY_PREVIOUS_FAILED_RULE_ID");
        if("true".equals(System.getenv("ENF_PENALTY_SEED_INPUT"))&&previous!=null&&!previous.isBlank()) {
            assertTrue(previous.startsWith("ENF15-POSITIVE-"));assertNotEquals(id,previous);
            var old=db.query("SELECT g.grant_id,g.grant_version FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.resource_type='PENALTY_RULE' AND g.resource_id=? AND g.action_code='READ' AND g.data_scope='EXPLICIT_SIMULATED_INPUT_NOT_LEGAL' AND g.status='ACTIVE'",(r,n)->new Epoch(r.getLong(2),r.getString(1)),issuer,subject,org,previous);
            assertEquals(1,old.size(),"Only the explicitly named failed input's controlled READ grant may be retired");
            watermark.change(()->grants.revoke(old.getFirst().grant(),old.getFirst().version(),"codex-functional-validation",UUID.randomUUID().toString(),"a".repeat(64)));
        }
        for(String action:List.of("READ","EDIT","TRIAL","PUBLISH","DISABLE"))issue(db,grants,watermark,issuer,subject,org,"PENALTY_RULE",id,revision,action,"PENALTY_ANALYSIS");
        issue(db,grants,watermark,issuer,subject,org,"LEGAL_REFERENCE",refId,1,"READ","LEGAL_BASIS");
        issue(db,grants,watermark,issuer,subject,org,"LEGAL_SOURCE",reference.sourceId(),1,"READ_VERBATIM","LEGAL_BASIS");
        issue(db,grants,watermark,issuer,subject,org,reference.targetType(),reference.targetId(),reference.targetRevision(),"READ_LEGAL_BASIS","LEGAL_BASIS");
        String client=req("ENF_PENALTY_PRODUCT_CLIENT_ID"),redirect="https://127.0.0.1:"+req("ENF_PENALTY_FRONTEND_PORT")+"/app/enforcement/penalty";
        var clients=new ProductClients(db,new ProductRegisteredClients(new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(db),db),tx,Clock.systemUTC());
        if(db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=?",Integer.class,client)==0)clients.register(new ProductClients.Input(client,"Enforcement penalty technical validation",redirect),UUID.randomUUID().toString(),"codex-functional-validation");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND redirect_uri=? AND status='ACTIVE'",Integer.class,client,redirect));
        json.writeValue(Path.of(req("ENF_PENALTY_PREP_EVIDENCE")).toFile(),Map.of("ruleId",id,"legalReference",reference,"clientId",client,"redirectUri",redirect,"grantRevision",revision,"scope","CONTROLLED_UPSTREAM_INPUT_AND_GRANTS_NOT_DOMAIN_I_OR_BUSINESS_ACCEPTANCE"));
    }
    private void issue(JdbcTemplate db,ProductGrants grants,ProductGrantWatermark watermark,String issuer,String subject,String org,String type,String id,long revision,String action,String purpose) {
        var rows=db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' AND resource_type=? AND resource_id=? AND action_code=? AND purpose=?",(r,n)->new Epoch(r.getLong(1),r.getString(2)),issuer,subject,org,type,id,action,purpose);
        long epoch=rows.isEmpty()?0:rows.getFirst().version();
        if(!rows.isEmpty()&&rows.getFirst().grant()!=null) {
            String old=rows.getFirst().grant();
            if(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? AND resource_version=? AND status='ACTIVE' AND expires_at>?",Integer.class,old,revision,java.sql.Timestamp.from(Instant.now().plusSeconds(600)))==1)return;
            long oldVersion=db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,old);
            epoch=watermark.change(()->grants.revoke(old,oldVersion,"codex-functional-validation",UUID.randomUUID().toString(),"e".repeat(64))).epoch();
        }
        long prior=epoch;watermark.change(()->grants.issue(new ProductGrants.Scope(issuer,subject,org,"ENFORCEMENT","ENF-PENALTY-FUNCTIONAL","EXPLICIT_SIMULATED_INPUT_NOT_LEGAL",type,id,revision,action,purpose),Instant.now().plusSeconds(7200),prior,"codex-functional-validation",UUID.randomUUID().toString(),"f".repeat(64)));
    }
    private record Epoch(long version,String grant) {}
    private static String req(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
