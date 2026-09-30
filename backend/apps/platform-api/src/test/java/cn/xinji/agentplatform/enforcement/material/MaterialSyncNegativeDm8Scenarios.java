package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

final class MaterialSyncNegativeDm8Scenarios {
    private MaterialSyncNegativeDm8Scenarios() { }

    static void run(MaterialRepository repository, MaterialProductService service, JdbcTemplate db,
                    JdbcTemplate authority, ProductGrantWatermark watermark, Jwt jwt, String issuer,
                    String actor, String sourceId, MaterialProductService.ObservationInput original) {
        var access = new ProductEnforcementAccess(db, authority, watermark, jwt, issuer, Clock.systemUTC());
        var malformed = new MaterialSourceSync(repository,
                (id, cursor) -> new MaterialPageSource.Page(List.of(), false, "bad-code"), service,
                access, Clock.systemUTC());
        assertThatThrownBy(() -> malformed.onePage(actor, sourceId))
                .isInstanceOf(MaterialPageSource.Failure.class);
        assertThat(repository.syncCursor(sourceId)).isEqualTo("p2-3");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE state='FAILED' "
                + "AND failure_code='PAGE_MALFORMED'", Integer.class)).isEqualTo(1);

        int observations = count(db, "enf3_observation");
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        var drift = new MaterialProductService.ObservationInput(sourceId, original.recordId(),
                original.sourceVersion(), original.text() + " changed", original.materialMention(),
                original.actorMentions());
        var changedVersion = new MaterialSourceSync(repository,
                (id, cursor) -> new MaterialPageSource.Page(List.of(
                        new MaterialPageSource.Entry("p2-4", drift)), true, null),
                service, access, Clock.systemUTC());
        var result = changedVersion.onePage(actor, sourceId);
        assertThat(result.state()).isEqualTo("PARTIAL_FAILED");
        assertThat(result.failureCode()).isEqualTo("INVALID_SOURCE_RECORD");
        assertThat(result.cursor()).isEqualTo("p2-3");
        assertThat(repository.syncCursor(sourceId)).isEqualTo("p2-3");
        assertThat(count(db, "enf3_observation")).isEqualTo(observations);
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE state='PARTIAL_FAILED' "
                + "AND failure_code='INVALID_SOURCE_RECORD' AND end_cursor='p2-3'",
                Integer.class)).isEqualTo(1);
        var record = repository.syncRecords(result.attemptId(), sourceId);
        assertThat(record).hasSize(1);
        assertThat(record.getFirst().recordId()).isEqualTo(original.recordId());
        assertThat(record.getFirst().sourceVersion()).isEqualTo(original.sourceVersion());
        assertThat(record.getFirst().beforeCursor()).isEqualTo("p2-3");
        assertThat(record.getFirst().state()).isEqualTo("FAILED");
        assertThat(record.getFirst().failureCode()).isEqualTo("INVALID_SOURCE_RECORD");
        System.out.printf("ENF_V2_M01_DRIFT_DM8 cursor=p2-3 failure=INVALID_SOURCE_RECORD "
                + "observations=%d receipts=%d events=%d%n", observations, receipts, events);
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
