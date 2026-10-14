package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles a real independent physical DM8 restore, including every penalty original. No conditional skip. */
class PenaltyRecoveryTargetDm8IT {
    @Test void independentPhysicalTargetMatchesAllSourceDomainRecordsAndOriginals() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var source=json.readTree(Files.readAllBytes(Path.of(required("ENF_PENALTY_SOURCE_INVENTORY"))));
        assertEquals("REAL_DM8_SOURCE_INVENTORY_NOT_PHYSICAL_RESTORE_OR_ACCEPTANCE",source.path("scope").asText());
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));assertNotEquals(required("ENF_PENALTY_SOURCE_URL"),url);
        var data=new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));var db=new JdbcTemplate(data);
        Path root=Path.of(required("ENF_PENALTY_RESTORE_ORIGINALS")).toRealPath();assertNotEquals(Path.of(required("ENF_PENALTY_ORIGINALS_BASE")).toRealPath(),root);
        var actual=db.queryForList("SELECT resource_kind,resource_id,revision,organization_id,state,payload,artifact_key,artifact_sha,actor_ref FROM enf11_penalty_revision ORDER BY resource_kind,resource_id,revision");
        var expected=json.createArrayNode();for(var record:source.path("records"))expected.add(record.path("row"));
        // DM8 returns BIGINT as LongNode; reading the retained JSON creates
        // IntNode for small values. Compare canonical JSON values, not Jackson
        // implementation classes; payload bytes and SHA are still exact below.
        assertEquals(expected,json.readTree(json.writeValueAsBytes(actual)));assertFalse(actual.isEmpty());
        var receipts=db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_receipt ORDER BY resource_kind,resource_id,revision");
        var events=db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_event ORDER BY resource_kind,resource_id,revision");
        assertEquals(source.path("receipts"),json.readTree(json.writeValueAsBytes(receipts)));assertEquals(source.path("events"),json.readTree(json.writeValueAsBytes(events)));assertEquals(receipts,events);
        var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
        for(var row:actual){String kind=row.get("RESOURCE_KIND").toString(),id=row.get("RESOURCE_ID").toString();long revision=((Number)row.get("REVISION")).longValue();
            Path file=root.resolve(row.get("ARTIFACT_KEY").toString());assertEquals(row.get("ARTIFACT_SHA"),PenaltyArtifacts.sha(Files.readAllBytes(file)));
            if("PENALTY_RULE".equals(kind))assertTrue(store.rule(id,revision).isPresent());else {assertEquals("PENALTY_SUGGESTION",kind);assertTrue(store.suggestion(id,revision).isPresent());}}
        try(var files=Files.list(root)){assertEquals(actual.size(),files.count());}
        Path output=Path.of(required("ENF_PENALTY_TARGET_INVENTORY"));assertFalse(Files.exists(output));
        json.writeValue(output.toFile(),Map.of("scope","REAL_INDEPENDENT_PHYSICAL_DM8_TARGET_RECONCILIATION_NOT_FINAL_ACCEPTANCE","dbUrl",url,"records",actual,"receipts",receipts,"events",events,"originalCount",actual.size(),"sourceTargetExact",true,"writeCount",0));
    }
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
