package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Request-local immutable read comparison only; not formal identity or admission evidence. */
final class ProductPatentSearchPageSnapshotAssertions {
    private ProductPatentSearchPageSnapshotAssertions() { }
    static List<ProductPatentSearchExecution.Member> consume(ProductPatentSearchExecution service,
            ProductPatentSearchExecution.Receipt receipt, org.springframework.security.oauth2.jwt.Jwt jwt) {
        var members = new java.util.ArrayList<ProductPatentSearchExecution.Member>();
        String cursor = null;
        do {
            var page = service.read("task", receipt.resultSetId(), 20, cursor, jwt);
            assertThat(page.members()).hasSizeLessThanOrEqualTo(20);
            assertThat(page.fieldEvidence()).isNull();
            assertThat(page.candidateCount()).isEqualTo(receipt.candidateCount());
            assertThat(page.methodVersion()).isEqualTo(receipt.methodVersion());
            assertThat(page.sourceOutcomes()).isEqualTo(receipt.sourceOutcomes());
            assertThat(page.status()).isEqualTo(receipt.status());
            assertThat(page.totalReliability()).isEqualTo(receipt.totalReliability());
            assertThat(page.citations()).hasSameSizeAs(page.members());
            for (int index=0;index<page.members().size();index++) {
                var citation=page.citations().get(index); var member=page.members().get(index);
                assertThat(citation.memberId()).isEqualTo(member.memberId());
                assertThat(citation.documentVersion()).isEqualTo(member.documentVersion());
                assertThat(citation.rawSha()).isEqualTo(member.rawSha());
                assertThat(citation.locatorRef()).isEqualTo(member.locatorRef());
            }
            members.addAll(page.members());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(members).hasSize(receipt.resultCount());
        return members;
    }
    private static final class Counted extends DelegatingDataSource {
        int sourceReads;
        Counted(JdbcTemplate db) { super(db.getDataSource()); }
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = super.getConnection();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null
                                && args[0] instanceof String sql
                                && sql.startsWith("SELECT s.source_id,s.contract_version")) sourceReads++;
                        try { return method.invoke(connection, args); }
                        catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                    });
        }
    }
    static void verify(JdbcTemplate db, ProductPatentSearchEvidenceCodec evidence,
            ProductCollectedPatentTitles titles, ProductSourceAvailability.Active active,
            ProductPatentSearchExecution.Receipt receipt, Clock clock) {
        var counted = new Counted(db);
        var pages = new ProductPatentSearchPages(new JdbcTemplate(counted), evidence,
                new ProductPatentSearchCursor(db, "c".repeat(32).getBytes(), Duration.ofMinutes(15), clock),
                titles, Map.of(active.sourceId(), active));
        var prepared = pages.prepare(receipt.resultSetId(), "member");
        var original = db.query("SELECT s.source_id,s.contract_version,s.decision_id,s.source_epoch "
                + "FROM con4_search_source s JOIN con4_result_set r ON r.attempt_id=s.attempt_id "
                + "WHERE r.result_set_id=? ORDER BY s.source_id", (rs,row) -> rs.getString(1)
                        + "\u0000" + rs.getString(2) + "\u0000" + rs.getString(3) + "\u0000" + rs.getLong(4),
                receipt.resultSetId());
        assertThat(prepared.context().sourceEpochSha()).isEqualTo(evidence.digest(original.toArray(String[]::new)));
        assertThat(prepared.receipt()).isEqualTo(receipt);
        assertThat(pages.read(receipt.resultSetId(), 20, 0, prepared).sourceOutcomes())
                .isEqualTo(receipt.sourceOutcomes());
        // One prepared receipt plus one independent historical isolation check per prepare/read.
        assertThat(counted.sourceReads).isEqualTo(3);
        assertThatThrownBy(() -> pages.read("other", 20, 0, prepared))
                .isInstanceOf(IllegalStateException.class).hasMessage("Committed search page binding differs");
        assertThatThrownBy(() -> pages.prepare("missing", "member"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(404));
    }
}
