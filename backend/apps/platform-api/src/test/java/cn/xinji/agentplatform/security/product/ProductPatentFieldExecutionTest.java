package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/** Fenced final-code field-submit/consume/fault C. Mechanical authority and text are not a product Source or I. */
class ProductPatentFieldExecutionTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"EXACT","HYBRID","MISSING_FIELD_TABLE",
            "HYBRID_MISSING_BUILD","HYBRID_FUTURE_BUILD","HYBRID_WRONG_TUPLE","HYBRID_CORRUPT_VECTOR"})
    void fieldRequestCommitsCompleteEvidenceUnderFenceOrRollsBackEverything(String scenario) throws Exception {
        boolean missingFieldTable="MISSING_FIELD_TABLE".equals(scenario),hybrid=scenario.startsWith("HYBRID");
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:field-execution-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(source);ProductFieldWitnessEngineeringFixture.executionParents(db);
        try(var connection=source.getConnection()) {
            for(String file:List.of("V039__product_source_verification.sql","V067__product_search_semantic_index.sql",
                    "V070__product_semantic_vector_snapshot_binding.sql","V040__product_source_results.sql","V063__product_search_execution_binding.sql",
                    "V064__product_search_ranking_evidence.sql","V065__product_search_stable_cursor.sql",
                    "V068__product_search_hybrid_evidence.sql","V069__product_search_query_expansion.sql",
                    "V071__product_search_rerank_evidence.sql","V072__product_search_metadata_filters.sql",
                    "V073__product_search_filter_evidence.sql","V074__product_search_confidence.sql",
                    "V079__product_search_digest_version.sql","V084__product_search_field_evidence.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8",file)));
        }
        Instant now=Instant.parse("2026-10-02T08:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        ProductFieldWitnessEngineeringFixture.executionInput(db,now);
        try(var connection=source.getConnection()) {
            for(String file:List.of("V077__product_execution_attempt_history.sql","V078__product_search_attempt_history_binding.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8",file)));
        }
        String documentId="US-20250149543-A1",query=hybrid?"mechanical":documentId;Set<String> fields=Set.of("ABSTRACT","CLAIMS");
        var mapper=new ObjectMapper().findAndRegisterModules();var filters=ProductPatentSearchFilters.none();
        db.update("UPDATE con3_search_input SET query_text=?,requested_fields_json=?,filters_json=?",query,
                mapper.writeValueAsString(new TreeSet<>(fields)),mapper.writeValueAsString(filters.evidence()));
        var active=new ProductSourceAvailability.Active("source","contract","mechanical-capture","decision",1,2,
                "mechanical/manifest.json","a".repeat(64),"b".repeat(64));
        var availability=mock(ProductSourceAvailability.class);
        for(String field:fields) when(availability.require("source",field,"QUERY")).thenReturn(active);
        when(availability.requireFields("source",fields,"QUERY")).thenReturn(active);
        var watermark=mock(ProductSourceWatermark.class);
        var workers=mock(ProductWorkerAccess.class);var request=mock(HttpServletRequest.class);
        when(workers.require(request,"task","input","delegation"))
                .thenReturn(new ProductWorkerAccess.Authorized("worker","task","input",2));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var execution=new ProductTaskWorkerExecution(workers,new ProductTaskExecutionFence(db,tx,clock),tx);
        var tasks=mock(ProductTaskDirectory.class);
        when(tasks.find("task")).thenReturn(Optional.of(new ProductTaskDirectory.Task("task","org","issuer","member",
                "IN_PROGRESS",2,1,"scope",1,now.plusSeconds(1800))));
        var consultation=mock(ProductConsultationAccess.class);when(consultation.permits(any(),any(),any())).thenReturn(true);
        var titles=mock(ProductCollectedPatentTitles.class);
        var doc=new PatentLexicalQuery.Document("source",documentId,"mechanical-v1",active.sourceVersion(),"c".repeat(64),
                "mechanical/raw#abstract",Map.of("ABSTRACT","mechanical identity projection","CLAIMS","mechanical content projection"));
        var locators=Map.of("ABSTRACT",doc.locatorRef(),"CLAIMS","mechanical/raw#claims");
        when(titles.loadFieldEvidence(eq(active),any(ProductPatentSearchFilters.class),eq(fields)))
                .thenReturn(List.of(new PatentFieldSemanticFusion.DocumentEvidence(doc,locators)));
        when(titles.fieldCitations(anyList(),eq(Map.of("source",active)),eq(fields))).thenAnswer(call -> {
            List<ProductPatentSearchExecution.Member> members=call.getArgument(0);
            return members.stream().map(member->new ProductPatentSearchCitations.Citation(member.memberId(),member.sourceId(),
                    member.documentId(),member.documentVersion(),member.sourceVersion(),"https://example.org/source", "https://example.org/raw",
                    now,member.rawSha(),member.locatorRef(),locators)).toList();
        });
        var cursor=new ProductPatentSearchCursor(db,new byte[32],Duration.ofMinutes(15),clock);
        ProductSemanticIndex semantic=null;PatentReranker reranker=null;
        if(hybrid) {
            var witness=ProductFieldWitnessEngineeringFixture.build(source,db,active,
                    new PatentFieldSemanticFusion.DocumentEvidence(doc,locators),fields,
                    Clock.fixed(now.minusSeconds(1),ZoneOffset.UTC));
            semantic=witness.index();
            assertThat(witness.models()).containsOnlyKeys(fields);
            reranker=new PatentReranker() {
                public Model model() { return new Model("mechanical-C","model","revision","a".repeat(64),"b".repeat(64),512,"mechanical-C"); }
                public List<Score> score(List<Pair> pairs) { throw new AssertionError("Old truncating score must not run"); }
                public List<Score> scoreComplete(List<Pair> pairs) {
                    assertThat(pairs.getFirst().documentText()).contains("ABSTRACT\n","CLAIMS\n").doesNotContain("TITLE","DESCRIPTION");
                    return pairs.stream().map(pair->new Score(pair.candidateId(),2)).toList();
                }
            };
        }
        var service=new ProductPatentSearchExecution(db,workers,execution,titles,availability,watermark,tasks,consultation,
                new ProductRetentionPolicy("policy",1,"d".repeat(64),Duration.ofMinutes(15)),mapper,cursor,semantic,null,reranker,clock,100);
        if(missingFieldTable) {
            db.execute("DROP TABLE con4_result_field_evidence");
            assertThatThrownBy(()->service.execute(request,"task","input","delegation","attempt",7,"field-request"))
                    .isInstanceOf(RuntimeException.class);
            for(String table:List.of("con4_search_attempt","con4_result_set","con4_result_member","con4_search_request","con4_search_field_scope"))
                assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT status FROM con3_execution_lease",String.class)).isEqualTo("ACTIVE");
            return;
        }
        var receipt=service.execute(request,"task","input","delegation","attempt",7,"field-request");
        assertThat(receipt.resultCount()).isEqualTo(1);assertThat(receipt.digestVersion()).isEqualTo(ProductPatentFieldResultEvidence.DIGEST_VERSION);
        assertThat(db.queryForObject("SELECT status FROM con3_execution_lease",String.class)).isEqualTo("COMMITTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_result_field_evidence",Integer.class)).isEqualTo(1);
        var codec=new ProductPatentSearchEvidenceCodec(mapper);var facts=codec.fieldStore(db).read(receipt.resultSetId());
        assertThat(facts.fields()).containsExactly("ABSTRACT","CLAIMS");
        if(hybrid) {
            assertThat(facts.models()).containsOnlyKeys(fields);assertThat(facts.rerankStatus()).isEqualTo("SUCCEEDED");
            assertThat(receipt.methodVersion()).isEqualTo("HYBRID_FIELD_RRF_COMPLETE_CE/v1");
            assertThat(receipt.confidenceReason()).isEqualTo("FIELD_CALIBRATION_UNAVAILABLE");
        } else assertThat(facts.models()).isEmpty();
        assertThat(service.execute(request,"task","input","delegation","attempt",7,"field-request")).isEqualTo(receipt);
        var historicalOrder=new TreeMap<String,Object>(Comparator.reverseOrder()); historicalOrder.putAll(filters.evidence());
        String historicalFilters=codec.json(historicalOrder);
        if(historicalFilters.equals(codec.json(filters.evidence()))) historicalFilters=codec.json(new TreeMap<>(filters.evidence()));
        String historicalScope=ProductPatentSearchFieldScope.from(List.copyOf(fields),filters).scopeSha(
                codec,List.of("source"),filters.version(),historicalFilters);
        db.update("UPDATE con4_search_attempt SET filters_json=?,filter_sha=?,scope_sha=? WHERE attempt_id='attempt'",
                historicalFilters,codec.digest(filters.version(),historicalFilters),historicalScope);
        db.update("UPDATE con4_search_field_scope SET scope_sha=? WHERE attempt_id='attempt'",historicalScope);
        assertThat(service.execute(request,"task","input","delegation","attempt",7,"field-request")).isEqualTo(receipt);
        db.update("UPDATE con4_search_attempt SET filter_sha=? WHERE attempt_id='attempt'","f".repeat(64));
        assertThatThrownBy(()->service.execute(request,"task","input","delegation","attempt",7,"field-request"))
                .hasMessage("Search request scope conflicts with committed input");
        db.update("UPDATE con4_search_attempt SET filter_sha=? WHERE attempt_id='attempt'",codec.digest(filters.version(),historicalFilters));
        var jwt=Jwt.withTokenValue("mechanical-C").header("alg","RS256").issuer("https://example.org/identity")
                .subject("member").issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        var page=service.read("task",receipt.resultSetId(),20,null,jwt);
        assertThat(page.fieldEvidence()).isEqualTo(facts);assertThat(page.members()).hasSize(1);
        assertThat(page.citations().getFirst().fieldLocators()).isEqualTo(locators);
        if(scenario.startsWith("HYBRID_")) {
            // Faults concern only this new ephemeral fixture. Original result/member/field SHA stay intact.
            String originalResultSha=receipt.resultSha();
            var originalFields=db.queryForList("SELECT member_id,fields_json,evidence_sha FROM con4_result_field_evidence WHERE result_set_id=?",
                    receipt.resultSetId());
            switch(scenario) {
                case "HYBRID_MISSING_BUILD" -> assertThat(db.update("DELETE FROM con4_vector_build WHERE source_id='source' AND field_name='CLAIMS'")).isEqualTo(1);
                case "HYBRID_FUTURE_BUILD" -> assertThat(db.update("UPDATE con4_vector_build SET completed_at=? WHERE source_id='source' AND field_name='CLAIMS'",
                        java.sql.Timestamp.from(now.plusSeconds(1)))).isEqualTo(1);
                case "HYBRID_WRONG_TUPLE" -> assertThat(db.update("UPDATE con4_vector_build SET source_epoch=source_epoch+1 WHERE source_id='source' AND field_name='CLAIMS'")).isEqualTo(1);
                case "HYBRID_CORRUPT_VECTOR" -> assertThat(db.update("UPDATE con4_document_vector SET vector_data=? WHERE source_id='source' AND field_name='CLAIMS'",
                        new byte[]{1,2,3})).isEqualTo(1);
                default -> throw new AssertionError("Unknown owned fixture fault");
            }
            if("HYBRID_CORRUPT_VECTOR".equals(scenario)) {
                assertThatThrownBy(()->service.read("task",receipt.resultSetId(),20,null,jwt))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Historical result evidence integrity differs");
            } else {
                assertThatThrownBy(()->service.read("task",receipt.resultSetId(),20,null,jwt))
                        .isInstanceOf(ProductHistoricalResultIsolation.Unverified.class)
                        .hasMessageContaining(ProductHistoricalResultIsolation.CODE);
                assertThatThrownBy(()->service.execute(request,"task","input","delegation","attempt",7,"field-request"))
                        .isInstanceOf(ProductHistoricalResultIsolation.Unverified.class)
                        .hasMessageContaining(ProductHistoricalResultIsolation.CODE);
            }
            assertThat(db.queryForObject("SELECT result_sha FROM con4_result_set WHERE result_set_id=?",String.class,
                    receipt.resultSetId())).isEqualTo(originalResultSha);
            assertThat(db.queryForList("SELECT member_id,fields_json,evidence_sha FROM con4_result_field_evidence WHERE result_set_id=?",
                    receipt.resultSetId())).isEqualTo(originalFields);
            return;
        }
        assertThatThrownBy(()->service.execute(request,"task","input","delegation","attempt",8,"old-token"))
                .isInstanceOf(IllegalStateException.class);
        when(availability.require("source","CLAIMS","QUERY")).thenThrow(new IllegalStateException("field withdrawn"));
        when(availability.requireFields("source",fields,"QUERY")).thenThrow(new IllegalStateException("field withdrawn"));
        assertThatThrownBy(()->service.execute(request,"task","input","delegation","attempt",7,"field-request")).hasMessage("field withdrawn");
        assertThatThrownBy(()->service.read("task",receipt.resultSetId(),20,null,jwt)).hasMessage("field withdrawn");
        doReturn(active).when(availability).require("source","CLAIMS","QUERY");
        doReturn(active).when(availability).requireFields("source",fields,"QUERY");
        when(workers.require(request,"task","input","delegation")).thenThrow(new IllegalStateException("worker revoked"));
        assertThatThrownBy(()->service.execute(request,"task","input","delegation","attempt",7,"field-request")).hasMessage("worker revoked");
        assertThat(codec.fieldStore(db).read(receipt.resultSetId())).isEqualTo(facts);
    }
}
