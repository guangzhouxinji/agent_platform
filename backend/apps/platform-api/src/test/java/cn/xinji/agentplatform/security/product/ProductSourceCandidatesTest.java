package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductSourceCandidatesTest {
    @Test void onlyCandidateAndWithdrawalArePersistedWithIdempotentAudit() throws Exception {
        JdbcDataSource source = database();
        script(source, "db/dm8/V036__product_source_candidates.sql");
        script(source, "db/dm8/V088__product_source_capture_identity.sql");
        JdbcTemplate db = new JdbcTemplate(source);
        var candidates = service(db, source);
        var input = declaration("source-1", "v1", "snapshot-1");

        assertThatThrownBy(() -> candidates.register(null, "first", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.register(declaration("source-1", "v1", "snapshot-1",
                Map.of("title", List.of("MODEL"))), "bad", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract", Integer.class)).isZero();
        var registered = candidates.register(input, "first", "operator#cert");
        assertThat(registered.status()).isEqualTo("CANDIDATE");
        assertThat(registered.retentionRef()).isEqualTo("source-retention-approval");
        assertThat(candidates.register(input, "first", "operator#cert")).isEqualTo(registered);
        assertThatThrownBy(() -> candidates.register(input, "another", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.register(declaration("source-1", "v2", "snapshot-1"),
                "second", "operator#cert")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.register(declaration("source-2", "v1", "snapshot-2"),
                "first", "operator#cert")).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_event", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_request", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract WHERE status='ACTIVE'",
                Integer.class)).isZero();

        assertThatThrownBy(() -> candidates.withdraw("source-1", "v1", 2, "wrong", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        var withdrawn = candidates.withdraw("source-1", "v1", 1, "withdraw", "operator#cert");
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.version()).isEqualTo(2);
        assertThat(candidates.withdraw("source-1", "v1", 1, "withdraw", "operator#cert"))
                .isEqualTo(withdrawn);
        assertThatThrownBy(() -> candidates.register(input, "first", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.withdraw("source-1", "v1", 1, "new", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_event", Integer.class)).isEqualTo(2);
        assertThat(service(db, source).read("source-1", "v1")).isEqualTo(withdrawn);
        script(source, "db/dm8/U036__product_source_candidates.sql");
    }

    @Test void failedEventInsertRollsBackCandidateAndReceipt() throws Exception {
        JdbcDataSource source = database();
        script(source, "db/dm8/V036__product_source_candidates.sql");
        script(source, "db/dm8/V088__product_source_capture_identity.sql");
        JdbcTemplate db = new JdbcTemplate(source);
        db.execute("ALTER TABLE con4_source_event ADD CONSTRAINT ck_probe_fail CHECK (operation <> 'REGISTER')");
        var service = service(db, source);
        assertThatThrownBy(() -> service.register(declaration("source-1", "v1", "s1"),
                "request", "operator#cert"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_request", Integer.class)).isZero();
    }

    @Test void sourceVersionUsesPhysicalBoundaryWithoutTruncatingOrWritingOverlongRequests() throws Exception {
        var source = database();
        script(source, "db/dm8/V036__product_source_candidates.sql");
        script(source, "db/dm8/V088__product_source_capture_identity.sql");
        var db = new JdbcTemplate(source);
        var candidates = service(db, source);
        var accepted = candidates.register(declaration("bounded", "v1", "a".repeat(100)),
                "boundary-100", "operator#cert");
        assertThat(accepted.sourceVersion()).isEqualTo("a".repeat(100));
        assertThatThrownBy(() -> candidates.register(declaration("overlong", "v1", "a".repeat(101)),
                "boundary-101", "operator#cert")).isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(400));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_event", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_request", Integer.class)).isEqualTo(1);
    }

    @Test void withdrawnCaptureCanHaveANewContractWithoutChangingContentOrRevivingOldContract() throws Exception {
        var source=database();script(source,"db/dm8/V036__product_source_candidates.sql");
        script(source,"db/dm8/V088__product_source_capture_identity.sql");var db=new JdbcTemplate(source);
        var candidates=service(db,source);var original=declaration("capture","v1","CAPTURE_SHA256:"+"c".repeat(64));
        var next=copy(original,"v2",original.licenseEvidenceSha256());
        candidates.register(original,"first-capture","operator#cert");
        assertThatThrownBy(()->candidates.register(next,"too-early","operator#cert")).isInstanceOf(ResponseStatusException.class);
        candidates.withdraw("capture","v1",1,"withdraw-capture","operator#cert");
        assertThatThrownBy(()->candidates.register(copy(original,"changed-license","d".repeat(64)),
                "changed-license","operator#cert")).isInstanceOf(ResponseStatusException.class);
        var renewed=candidates.register(next,"new-contract","operator#cert");
        assertThat(renewed.sourceVersion()).isEqualTo(original.sourceVersion());assertThat(renewed.status()).isEqualTo("CANDIDATE");
        assertThat(renewed.declaration().collectedAt()).isEqualTo(original.collectedAt());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_capture_identity",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->candidates.register(original,"revive-old","operator#cert")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->script(source,"db/dm8/U088__product_source_capture_identity.sql")).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_capture_identity",Integer.class)).isEqualTo(1);
        ProductSourceCaptureIdentity.verify(db,new ObjectMapper());
        String anchor=db.queryForObject("SELECT declaration_json FROM con4_source_capture_identity",String.class);
        var tampered=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(anchor);
        tampered.put("owner","changed-owner");
        db.update("UPDATE con4_source_capture_identity SET declaration_json=?",tampered.toString());
        assertThatThrownBy(()->ProductSourceCaptureIdentity.verify(db,new ObjectMapper())).hasMessage("Source capture identity differs");
    }
    @Test void concurrentFirstContractsSerializeOnTheSameImmutableCapture() throws Exception {
        var source=database();script(source,"db/dm8/V036__product_source_candidates.sql");
        script(source,"db/dm8/V088__product_source_capture_identity.sql");var db=new JdbcTemplate(source);
        var candidates=service(db,source);var original=declaration("parallel","v1","CAPTURE_SHA256:"+"c".repeat(64));
        var next=copy(original,"v2",original.licenseEvidenceSha256());var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();try{candidates.register(original,"a","operator#cert");return true;}
                catch(ResponseStatusException conflict){return false;}});
            var b=pool.submit(()->{start.await();try{candidates.register(next,"b","operator#cert");return true;}
                catch(ResponseStatusException conflict){return false;}});start.countDown();
            assertThat(java.util.stream.Stream.of(a.get(),b.get()).filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_capture_identity",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract",Integer.class)).isEqualTo(1);
    }
    private static ProductSourceCandidates.Declaration copy(ProductSourceCandidates.Declaration old,String version,String licenseSha) {
        return new ProductSourceCandidates.Declaration(old.sourceId(),version,old.sourceVersion(),old.provenance(),old.owner(),
                old.licenseEvidenceRef(),licenseSha,old.coverage(),old.requestedFields(),old.requestedUses(),old.searchableFields(),
                old.claimsAvailability(),old.fullTextAvailability(),old.queryCapability(),old.paginationCapability(),
                old.withdrawalCapability(),old.collectedAt(),old.validUntil().plusSeconds(60));
    }
    private static ProductSourceCandidates service(JdbcTemplate db, JdbcDataSource source) {
        return new ProductSourceCandidates(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper().findAndRegisterModules(),
                new ProductRetentionPolicy("source-retention-approval", 1, "a".repeat(64),
                        Duration.ofDays(2)), Clock.systemUTC());
    }

    private static ProductSourceCandidates.Declaration declaration(String source, String version, String sourceVersion) {
        return declaration(source, version, sourceVersion, Map.of("title", List.of("QUERY")));
    }

    private static ProductSourceCandidates.Declaration declaration(String source, String version,
            String sourceVersion, Map<String, List<String>> uses) {
        return new ProductSourceCandidates.Declaration(source, version, sourceVersion,
                "recorded-origin", "recorded-owner", "evidence-ref", "b".repeat(64),
                Map.of("jurisdiction", List.of("CN")), List.of("title"), uses, List.of("title"),
                false, false, true, true, true, Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(3600));
    }

    private static JdbcDataSource database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-candidates-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
