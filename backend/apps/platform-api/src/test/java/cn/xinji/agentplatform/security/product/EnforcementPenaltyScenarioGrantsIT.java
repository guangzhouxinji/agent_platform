package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Narrow explicitly requested technical grants ONLY; never domain I or real personnel authorization. */
class EnforcementPenaltyScenarioGrantsIT {
 @Test void provisionExactTechnicalScenarioScopes() throws Exception {
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
  var authority=new DriverManagerDataSource(req("ENF_ACCEPTANCE_AUTHORITY_URL"),req("ENF_ACCEPTANCE_AUTHORITY_USER"),req("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
   @Override public java.sql.Connection getConnection() throws java.sql.SQLException {var c=super.getConnection();try{c.setSchema("ENFACC_AUTH");return c;}catch(java.sql.SQLException e){c.close();throw e;}}
  };
  assertTrue(data.getUrl().startsWith("jdbc:dm://"));assertNotEquals(data.getUrl(),authority.getUrl());
  var db=new JdbcTemplate(data);var auth=new JdbcTemplate(authority);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
  var grants=new ProductGrants(db,tx,Clock.systemUTC());var issuer=req("ENF_ACCEPTANCE_ISSUER");
  var watermark=new ProductGrantWatermark(db,auth,issuer);var subject=req("ENF_ACCEPTANCE_SUBJECT");var org=req("ENF_ACCEPTANCE_ORGANIZATION");
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND status='ACTIVE'",Integer.class,issuer,subject));
  var json=new ObjectMapper().findAndRegisterModules();var scopes=json.readTree(Files.readAllBytes(Path.of(req("ENF_PENALTY_SCENARIO_SCOPES"))));assertTrue(scopes.isArray());assertFalse(scopes.isEmpty());
  String retire=System.getenv("ENF_PENALTY_RETIRE_PREPARATION_READ");
  if(retire!=null&&!retire.isBlank()) {
   assertTrue(retire.startsWith("ENF15-POSITIVE-"));
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_revision WHERE resource_id=?",Integer.class,retire),"Only empty failed-preparation resources may be retired");
   var old=db.query("SELECT g.grant_id,g.grant_version FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.resource_type='PENALTY_RULE' AND g.resource_id=? AND g.action_code='READ' AND g.data_scope='EXPLICIT_SIMULATED_INPUT_NOT_LEGAL' AND g.status='ACTIVE'",(r,n)->new Epoch(r.getLong(2),r.getString(1)),issuer,subject,org,retire);
   for(var grant:old)watermark.change(()->grants.revoke(grant.grant(),grant.version(),"codex-functional-validation",UUID.randomUUID().toString(),"c".repeat(64)));
  }
  for(var s:scopes) {
   String type=s.path("type").asText(),id=s.path("id").asText(),action=s.path("action").asText(),purpose=s.path("purpose").asText();long revision=s.path("revision").asLong();
   assertTrue(Set.of("PENALTY_RULE","PENALTY_SUGGESTION","CASE","EVIDENCE_ORGANIZATION","CONDUCT_ANALYSIS","PROCEDURE_REPORT","LEGAL_REFERENCE","LEGAL_SOURCE").contains(type));
   assertTrue(Set.of("READ","READ_REPORT","READ_VERBATIM","READ_LEGAL_BASIS","EDIT","TRIAL","PUBLISH","DISABLE","CREATE","RERUN","ADJUST","REVIEW").contains(action));
   assertTrue(Set.of("PENALTY_ANALYSIS","ANALYZE","DISPLAY","PROCEDURE_CHECK","LEGAL_BASIS").contains(purpose));assertFalse(id.isBlank());assertTrue(revision>0);
   if("LEGAL_BASIS".equals(purpose)) {
    assertTrue(Set.of("READ","READ_VERBATIM","READ_LEGAL_BASIS").contains(action));
    var owned=db.query("SELECT payload FROM enf9_legal_reference WHERE organization_id=? AND source_id LIKE 'PROJECT_SCORE_INPUT_%'",(r,n)->r.getString(1),org);
    boolean retained=false;for(var payload:owned){var ref=json.readTree(payload);String field="LEGAL_REFERENCE".equals(type)?"referenceId":"LEGAL_SOURCE".equals(type)?"sourceId":"targetId";if(id.equals(ref.path(field).asText()))retained=true;}
    assertTrue(retained,"Only retained project-owned technical input scopes may be renewed");
   }
   var rows=db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' AND resource_type=? AND resource_id=? AND action_code=? AND purpose=?",(r,n)->new Epoch(r.getLong(1),r.getString(2)),issuer,subject,org,type,id,action,purpose);
   long epoch=rows.isEmpty()?0:rows.getFirst().version();
   if(!rows.isEmpty()&&rows.getFirst().grant()!=null){var old=rows.getFirst().grant();
    if(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? AND resource_version=? AND status='ACTIVE' AND expires_at>?",Integer.class,old,revision,java.sql.Timestamp.from(Instant.now().plusSeconds(600)))==1)continue;
    long oldVersion=db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,old);
    epoch=watermark.change(()->grants.revoke(old,oldVersion,"codex-functional-validation",UUID.randomUUID().toString(),"a".repeat(64))).epoch();
   }
   long prior=epoch;watermark.change(()->grants.issue(new ProductGrants.Scope(issuer,subject,org,"ENFORCEMENT","ENF-PENALTY-FUNCTIONAL","EXPLICIT_SIMULATED_INPUT_NOT_LEGAL",type,id,revision,action,purpose),Instant.now().plusSeconds(7200),prior,"codex-functional-validation",UUID.randomUUID().toString(),"b".repeat(64)));
  }
  String client=System.getenv("ENF_PENALTY_SCENARIO_CLIENT_ID");
  if(client!=null&&!client.isBlank()){
   String redirect=req("ENF_PENALTY_SCENARIO_REDIRECT_URI");assertTrue(redirect.matches("https://127\\.0\\.0\\.1:[0-9]+/app/enforcement/penalty"));
   var clients=new ProductClients(db,new ProductRegisteredClients(new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(db),db),tx,Clock.systemUTC());
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=?",Integer.class,client));clients.register(new ProductClients.Input(client,"Penalty physical restore technical validation",redirect),UUID.randomUUID().toString(),"codex-functional-validation");
  }
  var heads=db.query("SELECT r.resource_id,r.revision,r.payload FROM enf11_penalty_revision r WHERE r.organization_id=? AND r.resource_kind='PENALTY_RULE' AND r.revision=(SELECT MAX(x.revision) FROM enf11_penalty_revision x WHERE x.resource_kind=r.resource_kind AND x.resource_id=r.resource_id) AND EXISTS (SELECT 1 FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.resource_id=r.resource_id AND g.resource_version=r.revision AND g.subject_id=? AND g.organization=r.organization_id AND g.action_code='READ' AND g.resource_type='PENALTY_RULE' AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP)",(r,n)->{com.fasterxml.jackson.databind.JsonNode payload;try{payload=json.readTree(r.getString(3));}catch(Exception e){throw new IllegalStateException(e);}var c=payload.path("calculation");String basis=c.path("basisRef").asText();long revision=c.path("basisRevision").asLong();int admitted=db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch e ON e.current_grant_id=g.grant_id WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.resource_type='LEGAL_REFERENCE' AND g.resource_id=? AND g.resource_version=? AND g.action_code='READ' AND g.purpose='LEGAL_BASIS' AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP",Integer.class,issuer,subject,org,basis,revision);return Map.of("id",r.getString(1),"revision",r.getLong(2),"basisRef",basis,"basisRevision",revision,"currentBasisGrantCount",admitted);},org,subject);
  json.writeValue(Path.of(req("ENF_PENALTY_SCENARIO_GRANT_EVIDENCE")).toFile(),Map.of("scope","TECHNICAL_GRANT_PREPARATION_NOT_DOMAIN_I_OR_BUSINESS_ACCEPTANCE","scopes",scopes,"readableRuleHeadsAudit",heads,"generation",watermark.currentGeneration(),"at",Instant.now().toString()));
 }
 private record Epoch(long version,String grant) {}
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
