package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPatentSearchExecutionTest {
    @TempDir Path temporary;

    @Test void realCapturedTitlesCommitUnderFenceAndRemainConsumable() throws Exception {
        Instant now = Instant.parse("2026-09-29T01:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product-search-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate db = new JdbcTemplate(source);
        parents(db);
        script(source, "db/dm8/V040__product_source_results.sql");
        script(source, "db/dm8/V063__product_search_execution_binding.sql");
        script(source, "db/dm8/V064__product_search_ranking_evidence.sql");
        script(source, "db/dm8/V065__product_search_stable_cursor.sql");
        script(source, "db/dm8/V068__product_search_hybrid_evidence.sql");
        script(source, "db/dm8/V069__product_search_query_expansion.sql");
        script(source, "db/dm8/V071__product_search_rerank_evidence.sql");
        script(source, "db/dm8/V072__product_search_metadata_filters.sql");
        script(source, "db/dm8/V073__product_search_filter_evidence.sql");
        script(source, "db/dm8/V074__product_search_confidence.sql");
        seed(db, now);

        Path capture = Path.of("data/patents/public-pilot-v1");
        Path target = temporary.resolve("capture");
        try (var paths = Files.walk(capture)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(capture.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var manifest = mapper.readTree(target.resolve("manifest.json").toFile());
        String sourceVersion = manifest.path("datasetId").asText() + ":"
                + manifest.path("generatedAt").asText();
        var active = new ProductSourceAvailability.Active("source", "contract", sourceVersion,
                "decision", 1, 2, "capture/manifest.json", sha(target.resolve("manifest.json")),
                manifest.path("recordsSha256").asText());
        ProductSourceAvailability availability = mock(ProductSourceAvailability.class);
        when(availability.require("source", "TITLE", "QUERY")).thenReturn(active);
        when(availability.require("source", "PUBLICATION_DATE", "QUERY")).thenReturn(active);
        when(availability.require("source", "IPC", "QUERY")).thenReturn(active);
        ProductSourceWatermark sourceWatermark = mock(ProductSourceWatermark.class);
        ProductWorkerAccess access = mock(ProductWorkerAccess.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        var worker = new ProductWorkerAccess.Authorized("worker", "task", "input", 2);
        doReturn(worker).when(access).require(request, "task", "input", "delegation");
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var fence = new ProductTaskExecutionFence(db, transactions, clock);
        var execution = new ProductTaskWorkerExecution(access, fence, transactions);
        ProductTaskDirectory tasks = mock(ProductTaskDirectory.class);
        var task = new ProductTaskDirectory.Task("task", "org", "issuer", "member",
                "IN_PROGRESS", 2, 1, "scope", 1, now.plusSeconds(1800));
        when(tasks.find("task")).thenReturn(java.util.Optional.of(task));
        ProductConsultationAccess consultationAccess = mock(ProductConsultationAccess.class);
        when(consultationAccess.permits(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var titleLoader = new ProductCollectedPatentTitles(
                new ProductSourceEvidenceFiles(temporary, 10_000_000), mapper);
        ProductSemanticIndex semantic = mock(ProductSemanticIndex.class);
        var semanticMatches = new java.util.ArrayList<ProductSemanticIndex.Match>();
        int semanticRank = 0;
        for (var document : titleLoader.load(active))
            semanticMatches.add(new ProductSemanticIndex.Match(document.sourceId(), document.documentId(),
                    document.documentVersion(), document.sourceVersion(), document.rawSha(),
                    document.locatorRef(), 1.0 - semanticRank++ / 100.0));
        when(semantic.search("source", "lithium battery accumulator", 1000)).thenReturn(semanticMatches);
        PatentReranker reranker = new PatentReranker() {
            @Override public Model model() {
                return new Model("ONNX_RUNTIME", "cross-encoder/ms-marco-MiniLM-L-6-v2",
                        "revision", "a".repeat(64), "b".repeat(64), 512, "Apache-2.0");
            }
            @Override public List<Score> score(List<Pair> pairs) {
                return pairs.stream().map(pair -> new Score(pair.candidateId(),
                        pair.documentText().toLowerCase().contains("lithium battery") ? 4.0 : -2.0)).toList();
            }
        };
        var service = new ProductPatentSearchExecution(db, access, execution, titleLoader,
                availability, sourceWatermark, tasks, consultationAccess,
                new ProductRetentionPolicy("result-policy", 1, "a".repeat(64), Duration.ofHours(1)),
                mapper, new ProductPatentSearchCursor(db, "c".repeat(32).getBytes(),
                        Duration.ofMinutes(15), clock), semantic, reranker, clock, 100);

        var receipt = service.execute(request, "task", "input", "delegation", "attempt", 7,
                "search-request");
        assertThat(receipt.resultCount()).isPositive();
        assertThat(receipt.methodVersion()).isEqualTo("HYBRID_RRF_CE/v1");
        assertThat(receipt.candidateCount()).isGreaterThanOrEqualTo(receipt.resultCount());
        assertThat(db.queryForObject("SELECT status FROM con3_execution_lease WHERE task_id='task'",
                String.class)).isEqualTo("COMMITTED");
        assertThat(db.queryForObject("SELECT fencing_token FROM con4_search_attempt WHERE attempt_id='attempt'",
                Long.class)).isEqualTo(7);
        assertThat(db.queryForObject("SELECT query_plan_version FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).isEqualTo("QUERY_EXPANSION/v1");
        assertThat(db.queryForObject("SELECT expansions_json FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).contains("accumulator", "RELATED_TERM");
        assertThat(db.queryForObject("SELECT expansion_sha FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).matches("[0-9a-f]{64}");
        assertThat(db.queryForObject("SELECT rerank_status FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT filter_version FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).isEqualTo("PATENT_FILTER/v1");
        assertThat(db.queryForObject("SELECT filters_json FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).contains("2020-01-01", "H01M");
        assertThat(db.queryForObject("SELECT filter_sha FROM con4_search_attempt "
                + "WHERE attempt_id='attempt'", String.class)).matches("[0-9a-f]{64}");
        assertThat(receipt.confidenceStatus()).isEqualTo("NORMAL");
        assertThat(receipt.confidenceRuleVersion()).isEqualTo("RETRIEVAL_CONFIDENCE/v1");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_result_member", Integer.class))
                .isEqualTo(receipt.resultCount());
        assertThat(service.execute(request, "task", "input", "delegation", "attempt", 7,
                "search-request")).isEqualTo(receipt);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("member")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        var members = new ArrayList<ProductPatentSearchExecution.Member>();
        String cursor = null;
        do {
            var page = service.read("task", receipt.resultSetId(), 20, cursor, jwt);
            assertThat(page.members()).hasSizeLessThanOrEqualTo(20);
            assertThat(page.candidateCount()).isEqualTo(receipt.candidateCount());
            assertThat(page.methodVersion()).isEqualTo(receipt.methodVersion());
            members.addAll(page.members());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(members).hasSize(receipt.resultCount());
        assertThat(members.stream().map(ProductPatentSearchExecution.Member::memberId))
                .doesNotHaveDuplicates();
        assertThat(new HashSet<>(members.stream()
                .map(ProductPatentSearchExecution.Member::ordinal).toList()))
                .hasSize(receipt.resultCount());
        assertThat(members).allSatisfy(member -> {
            assertThat(member.locatorRef()).endsWith("#title");
            assertThat(member.rawSha()).matches("[0-9a-f]{64}");
            assertThat(member.semanticRank()).isPositive();
            assertThat(member.semanticScore()).isNotNull();
            assertThat(member.fusionScore()).isPositive();
            assertThat(member.rerankScore()).isNotNull();
            assertThat(member.rerankRank()).isPositive();
            if (member.lexicalRank() == null) {
                assertThat(member.lexicalScore()).isNull();
                assertThat(member.matchedTerms()).isEmpty();
                assertThat(member.fieldScores()).isEmpty();
            } else {
                assertThat(member.matchFields()).containsExactly("TITLE");
                assertThat(member.lexicalScore()).isPositive();
                assertThat(member.matchedTerms()).isNotEmpty();
                assertThat(member.fieldScores()).containsOnlyKeys("TITLE");
            }
        });
        assertThat(mapper.valueToTree(members).toString()).doesNotContain("abstract", "claims", "fullText");

        db.update("UPDATE con3_search_input SET requested_fields_json=? WHERE input_id='input'",
                "[\"TITLE\",\"PUBLICATION_DATE\"]");
        assertThatThrownBy(() -> service.execute(request, "task", "input", "delegation",
                "attempt", 7, "search-request")).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con3_search_input SET requested_fields_json=? WHERE input_id='input'",
                "[\"TITLE\",\"PUBLICATION_DATE\",\"IPC\",\"ABSTRACT\"]");
        assertThatThrownBy(() -> service.execute(request, "task", "input", "delegation",
                "attempt", 7, "search-request")).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con3_search_input SET requested_fields_json=? WHERE input_id='input'",
                "[\"TITLE\",\"PUBLICATION_DATE\",\"IPC\"]");

        org.mockito.Mockito.doThrow(new IllegalStateException("Filter use expired"))
                .when(availability).require("source", "PUBLICATION_DATE", "QUERY");
        assertThatThrownBy(() -> service.execute(request, "task", "input", "delegation",
                "attempt", 7, "search-request")).isInstanceOf(IllegalStateException.class);
        doReturn(active).when(availability).require("source", "PUBLICATION_DATE", "QUERY");

        assertThatThrownBy(() -> service.execute(request, "task", "input", "delegation",
                "attempt", 6, "old-token")).isInstanceOf(IllegalStateException.class);
        when(access.require(request, "task", "input", "delegation"))
                .thenThrow(new IllegalStateException("Worker revoked"));
        assertThatThrownBy(() -> service.execute(request, "task", "input", "delegation",
                "attempt", 7, "search-request")).isInstanceOf(IllegalStateException.class);
    }

    @Test void emptyFilteredCandidatePoolIsSuccessfulAndDoesNotInvokeReranker() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProductSemanticIndex semantic = mock(ProductSemanticIndex.class);
        PatentReranker reranker = mock(PatentReranker.class);
        when(reranker.model()).thenReturn(new PatentReranker.Model("ONNX_RUNTIME", "model", "revision",
                "a".repeat(64), "b".repeat(64), 512, "Apache-2.0"));
        var lexical = new PatentBm25fQuery().search(List.of(), "no matching patent", Set.of("TITLE"), 1000);

        var result = new ProductPatentSearchRanking(new ProductPatentSearchEvidenceCodec(mapper),
                semantic, reranker, 100).rank(List.of("source"),
                        new PatentQueryExpansion().expand("no matching patent"), List.of(), lexical);

        assertThat(result.members()).isEmpty();
        assertThat(result.candidateCount()).isZero();
        assertThat(result.method()).isEqualTo("HYBRID_RRF_CE/v1");
        assertThat(result.rerankStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.confidence().status().name()).isEqualTo("NO_RESULT");
        verify(reranker, never()).score(org.mockito.ArgumentMatchers.anyList());
    }

    private static void parents(JdbcTemplate db) {
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36),organization_id VARCHAR(200),version BIGINT,"
                + "cancel_epoch BIGINT,state VARCHAR(24),expires_at TIMESTAMP,PRIMARY KEY(task_id),"
                + "UNIQUE(task_id,organization_id))");
        db.execute("CREATE TABLE con3_goal_current(task_id VARCHAR(36) PRIMARY KEY,goal_id VARCHAR(36),"
                + "revision BIGINT,task_version BIGINT)");
        db.execute("CREATE TABLE con3_search_input(input_id VARCHAR(36),task_id VARCHAR(36),goal_id VARCHAR(36),"
                + "goal_revision BIGINT,task_version BIGINT,query_text CLOB,source_scope_json CLOB,"
                + "requested_fields_json CLOB,content_sha VARCHAR(64),confirmed_at TIMESTAMP,expires_at TIMESTAMP,"
                + "PRIMARY KEY(input_id),UNIQUE(task_id,input_id))");
        db.execute("CREATE TABLE con3_execution_lease(task_id VARCHAR(36) PRIMARY KEY,attempt_id VARCHAR(36) UNIQUE,"
                + "input_id VARCHAR(36),task_version BIGINT,cancel_epoch BIGINT,fencing_token BIGINT,"
                + "worker_ref VARCHAR(200),status VARCHAR(24),lease_until TIMESTAMP,updated_at TIMESTAMP)");
        db.execute("CREATE TABLE con3_execution_dispatch(task_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "fencing_token BIGINT,status VARCHAR(24))");
        db.execute("CREATE TABLE con4_source_contract(source_id VARCHAR(200),contract_version VARCHAR(100),"
                + "PRIMARY KEY(source_id,contract_version))");
        db.execute("CREATE TABLE con4_source_admission(decision_id VARCHAR(36),source_id VARCHAR(200),"
                + "contract_version VARCHAR(100),PRIMARY KEY(decision_id),"
                + "UNIQUE(decision_id,source_id,contract_version))");
    }

    private static void seed(JdbcTemplate db, Instant now) {
        db.update("INSERT INTO con3_task VALUES(?,?,?,?,?,?)", "task", "org", 2, 0,
                "IN_PROGRESS", Timestamp.from(now.plusSeconds(1800)));
        db.update("INSERT INTO con3_goal_current VALUES(?,?,?,?)", "task", "goal", 1, 2);
        db.update("INSERT INTO con3_search_input VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", "input", "task",
                "goal", 1, 2, "lithium battery", "[\"source\"]",
                "[\"TITLE\",\"PUBLICATION_DATE\",\"IPC\"]",
                "b".repeat(64), Timestamp.from(now.minusSeconds(1)), Timestamp.from(now.plusSeconds(1800)),
                "{\"publicationFrom\":\"2020-01-01\",\"ipcPrefixes\":[\"H01M\"],\"cpcPrefixes\":[]}");
        db.update("INSERT INTO con3_execution_lease VALUES(?,?,?,?,?,?,?,?,?,?)", "task", "attempt",
                "input", 2, 0, 7, "worker", "ACTIVE", Timestamp.from(now.plusSeconds(300)),
                Timestamp.from(now));
        db.update("INSERT INTO con4_source_contract VALUES('source','contract')");
        db.update("INSERT INTO con4_source_admission VALUES('decision','source','contract')");
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }

    private static String sha(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
