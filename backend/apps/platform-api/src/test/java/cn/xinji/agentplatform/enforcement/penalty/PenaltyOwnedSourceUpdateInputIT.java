package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Changes an explicitly project-authored score INPUT, never invents an updated law or legal finding. */
class PenaltyOwnedSourceUpdateInputIT {
 @Test void retainOriginalAndAppendExplicitTechnicalInputRevision() throws Exception {
  var json=new ObjectMapper();Path catalog=Path.of(req("ENF_PENALTY_CURRENT_CATALOG")),out=Path.of(req("ENF_PENALTY_SOURCE_UPDATE_EVIDENCE"));assertFalse(Files.exists(out));Files.createDirectory(out);
  byte[] before=Files.readAllBytes(catalog);var value=(ObjectNode)json.readTree(before);Files.write(out.resolve("catalog-before.json"),before);
  var entries=(ArrayNode)value.path("sources");ObjectNode selected=null;for(var item:entries)if(item.path("sourceId").asText().equals(req("ENF_PENALTY_UPDATED_SOURCE_ID")))selected=(ObjectNode)item;
  assertNotNull(selected);assertTrue(selected.path("sourceId").asText().startsWith("PROJECT_SCORE_INPUT_"));String oldVersion=selected.path("sourceVersion").asText();assertFalse(oldVersion.contains("technical-revision"));
  var oldCandidate=selected.path("candidates").get(0);String oldText=oldCandidate.path("verbatimText").asText();assertTrue(oldText.startsWith("PROJECT_AUTHORED_FICTIONAL / FUNCTIONAL_TEST_INPUT ONLY"));
  String newVersion=oldVersion+"-technical-revision-2",newText=oldText+"\nTechnical source revision 2 retains the project score formula. This is input-change testing, NOT an updated statute or business conclusion.";
  Path newOriginal=catalog.getParent().resolve("owned-score-input-revision-2.txt");assertFalse(Files.exists(newOriginal));Files.writeString(newOriginal,newText);String newSha=PenaltyArtifacts.sha(Files.readAllBytes(newOriginal));
  var updated=selected.deepCopy();updated.put("sourceRevision",2);updated.put("sourceVersion",newVersion);for(var node:updated.path("candidates")){var c=(ObjectNode)node;c.put("sourceRevision",2);c.put("sourceVersion",newVersion);c.put("verbatimText",newText);c.put("verbatimSha256",newSha);c.put("explanation","Explicit project-owned input revision; not a statutory change or real professional confirmation");}entries.add(updated);
  Path retained=Path.of(value.path("provenance").path("snapshot").asText());byte[] retainedBytes=Files.readAllBytes(retained);String retainedSha=PenaltyArtifacts.sha(retainedBytes);assertEquals(value.path("provenance").path("snapshotSha256").asText(),retainedSha);
  var manifest=json.createObjectNode();manifest.put("scope","PROJECT_OWNED_TECHNICAL_INPUT_CHANGE_NOT_NEW_LAW");manifest.put("retainedSnapshot",retained.toString());manifest.put("retainedSnapshotSha256",retainedSha);manifest.put("newOriginal",newOriginal.toString());manifest.put("newOriginalSha256",newSha);manifest.set("oldSource",selected);manifest.set("updatedSource",updated);Path nextManifest=catalog.getParent().resolve("owned-source-change-manifest.json");assertFalse(Files.exists(nextManifest));json.writeValue(nextManifest.toFile(),manifest);
  var provenance=(ObjectNode)value.path("provenance");provenance.put("snapshot",nextManifest.toString());provenance.put("snapshotSha256",PenaltyArtifacts.sha(Files.readAllBytes(nextManifest)));provenance.put("capturedAt",Instant.now().toString());json.writeValue(catalog.toFile(),value);
  var source=new cn.xinji.agentplatform.enforcement.legalreference.FileLegalKnowledgeSource(catalog,json);assertEquals(oldCandidate.path("verbatimSha256").asText(),source.exact(selected.path("sourceId").asText(),oldVersion,oldCandidate.path("candidateId").asText()).verbatimSha256());assertEquals(newSha,source.latest(selected.path("sourceId").asText(),oldCandidate.path("articleId").asText()).verbatimSha256());assertEquals(retainedSha,PenaltyArtifacts.sha(Files.readAllBytes(retained)));
  Files.copy(catalog,out.resolve("catalog-after.json"));json.writeValue(out.resolve("result.json").toFile(),Map.of("scope","PROJECT_OWNED_SOURCE_INPUT_PREPARATION_NOT_DOMAIN_I_OR_LEGAL_ACCEPTANCE","sourceId",selected.path("sourceId").asText(),"oldVersion",oldVersion,"newVersion",newVersion,"oldSha",oldCandidate.path("verbatimSha256").asText(),"newSha",newSha,"oldOriginalRetained",true,"retainedSnapshotSha256",retainedSha));
 }
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
