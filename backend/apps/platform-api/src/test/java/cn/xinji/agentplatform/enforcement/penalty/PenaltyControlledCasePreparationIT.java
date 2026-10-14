package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit substitute input preparation; NOT domain I, upstream acceptance or human review. */
class PenaltyControlledCasePreparationIT {
 @Test void importExplicitControlledCaseIntoActualStores() throws Exception {
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
  var authority=new DriverManagerDataSource(req("ENF_ACCEPTANCE_AUTHORITY_URL"),req("ENF_ACCEPTANCE_AUTHORITY_USER"),req("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
   @Override public java.sql.Connection getConnection() throws java.sql.SQLException {var c=super.getConnection();try{c.setSchema("ENFACC_AUTH");return c;}catch(java.sql.SQLException e){c.close();throw e;}}
  };
  assertTrue(data.getUrl().startsWith("jdbc:dm://"));assertNotEquals(data.getUrl(),authority.getUrl());
  var db=new JdbcTemplate(data);var auth=new JdbcTemplate(authority);var clock=Clock.systemUTC();
  // Install only missing, already frozen independent-authority migrations. Never rewrite watermark state.
  var deploy=new DriverManagerDataSource(req("ENF_ACCEPTANCE_AUTHORITY_URL"),"ENFACC_AUTH",req("ENF_ACCEPTANCE_AUTHORITY_DEPLOY_PASSWORD"));
  try(var connection=deploy.getConnection();var statement=connection.createStatement()) {
   for(var name:java.util.List.of("V002__enforcement_source_watermark.sql","V005__enforcement_delete_watermark.sql")) {
    String table=name.startsWith("V002")?"ENF3_SOURCE_WATERMARK":"ENF3_DELETE_WATERMARK";
    try(var query=connection.prepareStatement("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?")) {
     query.setString(1,table);try(var rows=query.executeQuery()){rows.next();if(rows.getInt(1)==0)org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource(Path.of(req("ENF_PENALTY_WORKSPACE"),"db/identity-authority",name)));}
    }
    statement.execute("GRANT SELECT, INSERT, UPDATE ON "+table+" TO ENFACC_ASVC");
   }
  }
  var watermark=new ProductGrantWatermark(db,auth,req("ENF_ACCEPTANCE_ISSUER"));
  var access=new ProductEnforcementAccess(db,auth,watermark,null,req("ENF_ACCEPTANCE_ISSUER"),clock);
  var json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  Path root=Path.of(req("ENF_PENALTY_CASE_INPUT_ROOT"));
  var result=PenaltyControlledUpstreamInput.prepare(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,root,req("ENF_PENALTY_RUN_ID"),req("ENF_ACCEPTANCE_ORGANIZATION"),access);
  assertTrue(Files.isRegularFile(root.resolve("input-manifest.json")));
  assertEquals(result.get("materialSha256"),PenaltyArtifacts.sha(Files.readAllBytes(root.resolve("original.txt"))));
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM enf3_material_action WHERE material_id=?",Integer.class,result.get("materialId")));
  json.writeValue(Path.of(req("ENF_PENALTY_CASE_PREP_EVIDENCE")).toFile(),result);
 }
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
