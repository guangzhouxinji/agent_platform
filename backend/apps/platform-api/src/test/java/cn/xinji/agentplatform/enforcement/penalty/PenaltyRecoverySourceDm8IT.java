package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual source inventory only: not a physical backup, restored runtime or final acceptance. */
class PenaltyRecoverySourceDm8IT {
    @Test void everyPersistedPenaltyRevisionHasAnExactOriginalAndOneReceiptEvent() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var data=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        assertTrue(data.getUrl().startsWith("jdbc:dm://"));var db=new JdbcTemplate(data);
        Path vault=Path.of(required("ENF_PENALTY_ORIGINALS_BASE")).toRealPath();
        var originals=new HashMap<String,List<Path>>();
        String configured=System.getenv("ENF_PENALTY_SOURCE_FILE_ROOT");
        var roots=configured==null||configured.isBlank()?List.of(vault.resolve("penalty-originals"),vault.resolve("penalty-product"),vault.resolve("penalty-positive-originals")):List.of(Path.of(configured).toRealPath());
        for(Path root:roots) {
            assertTrue(root.startsWith(vault),"Actual original root must remain in the product vault");assertTrue(Files.isDirectory(root),"Actual original root missing: "+root);
            try(var walk=Files.walk(root)) { for(Path file:walk.filter(Files::isRegularFile).toList()) {
                Path exact=file.toRealPath();assertTrue(exact.startsWith(root.toRealPath()),"Original escaped root");
                originals.computeIfAbsent(exact.getFileName().toString(),k->new ArrayList<>()).add(exact);
            }}
        }
        String query="SELECT resource_kind,resource_id,revision,organization_id,state,payload,artifact_key,artifact_sha,actor_ref FROM enf11_penalty_revision ORDER BY resource_kind,resource_id,revision";
        var before=db.queryForList(query);assertFalse(before.isEmpty(),"Actual product domain records required; no empty-table pass");
        var receipts=db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_receipt ORDER BY resource_kind,resource_id,revision");
        var events=db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_event ORDER BY resource_kind,resource_id,revision");
        assertEquals(before.size(),receipts.size());assertEquals(receipts,events,"No half/different receipt and event");
        var inventory=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var row:before) {
            String kind=row.get("RESOURCE_KIND").toString(),id=row.get("RESOURCE_ID").toString();
            long version=((Number)row.get("REVISION")).longValue();String coordinate=kind+"|"+id+"|"+version;
            assertTrue(seen.add(coordinate));String key=row.get("ARTIFACT_KEY").toString(),sha=row.get("ARTIFACT_SHA").toString();
            assertTrue(key.matches("[a-f0-9-]{36}\\.json"));assertTrue(sha.matches("[a-f0-9]{64}"));
            var candidates=originals.getOrDefault(key,List.of());assertEquals(1,candidates.size(),"Missing/ambiguous original: "+coordinate);
            Path file=candidates.getFirst();byte[] bytes=Files.readAllBytes(file);
            assertEquals(sha,PenaltyArtifacts.sha(bytes));assertArrayEquals(row.get("PAYLOAD").toString().getBytes(StandardCharsets.UTF_8),bytes,"DB/file exact bytes");
            var payload=json.readTree(bytes);assertEquals(id,payload.path("id").asText());assertEquals(version,payload.path("revision").asLong());
            assertEquals(row.get("STATE").toString(),payload.path("state").asText());assertEquals(row.get("ORGANIZATION_ID").toString(),payload.path("organization").asText());
            var matches=receipts.stream().filter(r->kind.equals(r.get("RESOURCE_KIND"))&&id.equals(r.get("RESOURCE_ID"))&&((Number)r.get("REVISION")).longValue()==version).toList();
            assertEquals(1,matches.size());assertEquals(row.get("ACTOR_REF"),matches.getFirst().get("ACTOR_REF"));
            inventory.add(Map.of("coordinate",coordinate,"row",row,"originalPath",file.toString(),"sha256",sha));
        }
        assertEquals(before,db.queryForList(query),"Snapshot source changed during capture; must retry, not claim consistent backup");
        assertEquals(receipts,db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_receipt ORDER BY resource_kind,resource_id,revision"));
        assertEquals(events,db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_event ORDER BY resource_kind,resource_id,revision"));
        Path output=Path.of(required("ENF_PENALTY_SOURCE_INVENTORY"));assertFalse(Files.exists(output),"Fresh source evidence required");
        json.writeValue(output.toFile(),Map.of("scope","REAL_DM8_SOURCE_INVENTORY_NOT_PHYSICAL_RESTORE_OR_ACCEPTANCE","capturedAt",Instant.now(),"records",inventory,"receipts",receipts,"events",events,"writeCount",0,"physicalRestoreExecuted",false));
    }
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
