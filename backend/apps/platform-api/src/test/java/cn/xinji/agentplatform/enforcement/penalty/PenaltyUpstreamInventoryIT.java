package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only preparation inventory. Never counts as an ENF16 product generation or domain-I pass. */
class PenaltyUpstreamInventoryIT {
 @Test void inventoryExistingExactUpstreamCoordinatesWithoutChangingFrozenData() throws Exception {
  var source=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));assertTrue(source.getUrl().startsWith("jdbc:dm://"));
  var db=new JdbcTemplate(source);var json=new ObjectMapper().findAndRegisterModules();String org=req("ENF_ACCEPTANCE_ORGANIZATION");
  var conduct=payloads(db,json,"SELECT r.payload FROM enf7_conduct_report r WHERE organization_id=? AND revision=(SELECT MAX(x.revision) FROM enf7_conduct_report x WHERE x.analysis_id=r.analysis_id)",org);
  var procedure=payloads(db,json,"SELECT r.payload FROM enf6_procedure_report r WHERE organization_id=? AND revision=(SELECT MAX(x.revision) FROM enf6_procedure_report x WHERE x.report_id=r.report_id)",org);
  var evidence=payloads(db,json,"SELECT r.payload FROM enf4_organization r WHERE owner_organization=? AND revision=(SELECT MAX(x.revision) FROM enf4_organization x WHERE x.organization_id=r.organization_id)",org);
  var candidates=new ArrayList<Map<String,Object>>();
  for(var c:conduct) for(var p:procedure) for(var e:evidence) {
   if(!c.path("caseRef").asText().equals(p.path("caseRef").asText())||!c.path("caseVersion").asText().equals(p.path("caseVersion").asText())||!c.path("caseRef").asText().equals(e.path("caseRef").asText())||!c.path("evidenceOrganizationId").asText().equals(e.path("id").asText())||c.path("evidenceOrganizationVersion").asLong()!=e.path("version").asLong())continue;
   var gaps=new ArrayList<String>();
   if(!"REVIEWED".equals(c.path("reviewState").asText())||c.path("reviewedBy").isNull()||!c.path("gaps").isEmpty())gaps.add("CONDUCT_CONFIRMATION_OR_GAP");
   if(!"COMPLETE".equals(p.path("executionState").asText())||!p.path("unprocessed").isEmpty()||p.path("items").isEmpty())gaps.add("PROCEDURE_COMPLETENESS");
   p.path("items").forEach(i->{if(!"PASS".equals(i.path("state").asText()))gaps.add("PROCEDURE_ITEM_NOT_PASS");});
   if(!"CONFIRMED".equals(e.path("state").asText())||e.path("reviewedBy").isNull()||!e.path("unprocessed").isEmpty()||e.path("matters").isEmpty())gaps.add("EVIDENCE_CONFIRMATION_OR_GAP");
   e.path("matters").forEach(m->{if(!"PRESENT".equals(m.path("coverage").asText()))gaps.add("EVIDENCE_MATTER_NOT_PRESENT");});
   candidates.add(Map.of("caseRef",c.path("caseRef").asText(),"caseVersion",c.path("caseVersion").asText(),"conduct",c,"procedure",p,"evidence",e,"gaps",gaps,"scope","CANDIDATE_INPUT_NOT_PRODUCT_VALIDATED"));
  }
  json.writeValue(Path.of(req("ENF_PENALTY_UPSTREAM_INVENTORY")).toFile(),Map.of("scope","READ_ONLY_PREPARATION_NOT_DOMAIN_I_OR_PRODUCT_ACCEPTANCE","capturedAt",Instant.now().toString(),"organization",org,"conductHeads",conduct,"procedureHeads",procedure,"evidenceHeads",evidence,"coherentCandidates",candidates,"writes",0));
 }
 private static List<JsonNode> payloads(JdbcTemplate db,ObjectMapper json,String sql,String org){return db.query(sql,(r,n)->{try{return json.readTree(r.getString(1));}catch(Exception e){throw new IllegalStateException("Stored upstream input unreadable",e);}},org);}
 private static String req(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
