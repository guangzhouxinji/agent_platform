package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Deployment input consolidation only: no business DB writes, synthetic pass or human/legal assertion. */
class PenaltyRuntimeInputPreparationIT {
 @Test void consolidateAllRetainedOriginalsAndCatalogInputsWithoutChangingThem() throws Exception {
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));assertTrue(data.getUrl().startsWith("jdbc:dm://"));var db=new JdbcTemplate(data);
  var json=new ObjectMapper().findAndRegisterModules();Path vault=Path.of(req("ENF_PENALTY_ORIGINALS_BASE")).toRealPath(),root=Path.of(req("ENF_PENALTY_RUNTIME_INPUT_ROOT"));assertFalse(Files.exists(root));Files.createDirectories(root);Path originals=root.resolve("penalty-originals");Files.createDirectory(originals);
  var lookup=new HashMap<String,List<Path>>();
  for(String directory:List.of("penalty-originals","penalty-product","penalty-positive-originals"))try(var walk=Files.walk(vault.resolve(directory))){for(var f:walk.filter(Files::isRegularFile).toList()){var actual=f.toRealPath();assertTrue(actual.startsWith(vault.resolve(directory).toRealPath()));lookup.computeIfAbsent(f.getFileName().toString(),k->new ArrayList<>()).add(actual);}}
  String query="SELECT resource_kind,resource_id,revision,artifact_key,artifact_sha,payload FROM enf11_penalty_revision ORDER BY resource_kind,resource_id,revision";var before=db.queryForList(query);assertFalse(before.isEmpty());var copied=new ArrayList<Map<String,String>>();
  for(var row:before){String key=row.get("ARTIFACT_KEY").toString(),sha=row.get("ARTIFACT_SHA").toString();var matches=lookup.getOrDefault(key,List.of());assertEquals(1,matches.size(),"Missing/ambiguous retained original "+key);var source=matches.getFirst();byte[] bytes=Files.readAllBytes(source);assertEquals(sha,PenaltyArtifacts.sha(bytes));assertArrayEquals(row.get("PAYLOAD").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),bytes);Path target=originals.resolve(key);Files.copy(source,target);assertEquals(sha,PenaltyArtifacts.sha(Files.readAllBytes(target)));copied.add(Map.of("source",source.toString(),"target",target.toString(),"sha256",sha));}
  assertEquals(before,db.queryForList(query),"Domain changed while preparing input checkpoint");
  var catalogs=new ArrayList<Path>();try(var walk=Files.walk(vault.resolve("penalty-positive-input"))){catalogs.addAll(walk.filter(p->p.getFileName().toString().equals("catalog.json")).sorted().toList());}catalogs.add(Path.of(req("ENF_PENALTY_WORKSPACE"),"data/enforcement/legal-reference-v2-product-input-v1/catalog.json"));
  var entries=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();ArrayNode retained=json.createArrayNode();int ordinal=0;
  Path provenanceRoot=root.resolve("retained-source-inputs");Files.createDirectory(provenanceRoot);
  for(Path catalog:catalogs){var value=json.readTree(Files.readAllBytes(catalog));var provenance=value.path("provenance");Path snapshot=Path.of(provenance.path("snapshot").asText());if(!snapshot.isAbsolute())snapshot=Path.of(req("ENF_PENALTY_WORKSPACE")).resolve(snapshot).normalize();byte[] bytes=Files.readAllBytes(snapshot);String sha=PenaltyArtifacts.sha(bytes);assertEquals(provenance.path("snapshotSha256").asText(),sha);Path archive=provenanceRoot.resolve(String.format("%03d-original",++ordinal));Files.copy(snapshot,archive);
   var item=json.createObjectNode();item.put("sourceCatalog",catalog.toString());item.set("originalProvenance",provenance);item.put("retainedOriginal",archive.toString());item.put("sha256",sha);item.set("catalog",value);retained.add(item);
   for(var entry:value.path("sources")){String key=entry.path("sourceId").asText()+"|"+entry.path("sourceVersion").asText();var existing=entries.putIfAbsent(key,entry);if(existing!=null)assertEquals(existing,entry,"Conflicting retained source version, never guess");}
  }
  Path archive=provenanceRoot.resolve("source-input-manifest.json");json.writeValue(archive.toFile(),retained);
  var merged=json.createObjectNode();ArrayNode sources=json.createArrayNode();entries.values().forEach(sources::add);merged.set("sources",sources);
  merged.set("provenance",json.valueToTree(Map.of("provider","PROJECT_RETAINED_INPUT_COLLECTION_NOT_NEW_LAW","sourceUrl","urn:project:retained-inputs:"+req("ENF_PENALTY_RUN_ID"),"snapshot",archive.toString(),"snapshotSha256",PenaltyArtifacts.sha(Files.readAllBytes(archive)),"capturedAt",Instant.now().toString(),"purpose","EXISTING_FINAL_PRODUCT_TECHNICAL_INPUT_CONFIGURATION","legalApplicability","NO_NEW_LEGAL_OR_BUSINESS_ASSERTION")));
  Path catalog=root.resolve("catalog.json");json.writeValue(catalog.toFile(),merged);
  new cn.xinji.agentplatform.enforcement.legalreference.FileLegalKnowledgeSource(catalog,json).exact(sources.get(0).path("sourceId").asText(),sources.get(0).path("sourceVersion").asText(),sources.get(0).path("candidates").get(0).path("candidateId").asText());
  json.writeValue(Path.of(req("ENF_PENALTY_RUNTIME_INPUT_EVIDENCE")).toFile(),Map.of("scope","DEPLOYMENT_INPUT_CONSOLIDATION_NOT_DOMAIN_I_OR_BUSINESS_ACCEPTANCE","originalRoot",originals.toString(),"catalog",catalog.toString(),"retainedSourceCount",entries.size(),"retainedCatalogCount",catalogs.size(),"originals",copied,"domainWrites",0,"historicalOriginalsDeleted",0));
 }
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
