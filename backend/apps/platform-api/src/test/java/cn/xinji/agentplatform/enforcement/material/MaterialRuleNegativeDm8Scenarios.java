package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialRuleNegativeDm8Scenarios {
    private MaterialRuleNegativeDm8Scenarios() { }

    static void run(MaterialProductService service, JdbcTemplate db, JdbcTemplate authority,
                    ProductGrants grants, ProductGrantWatermark watermark, String issuer,
                    String actor, String org, String ruleId, String originalText) {
        String otherSource = "PROJECT-DEV-OTHER-SOURCE";
        grant(grants, watermark, issuer, actor, org, "ENFORCEMENT_SOURCE", otherSource,
                "CONFIGURE", "SOURCE_MANAGEMENT");
        grant(grants, watermark, issuer, actor, org, "ENFORCEMENT_SOURCE", otherSource,
                "SYNC", "SAVE");
        grant(grants, watermark, issuer, actor, org, "ENFORCEMENT_SOURCE", otherSource,
                "SYNC", "QUERY");
        service.registerSource(actor, "other-source", new MaterialProductService.SourceInput(otherSource,
                org, "docs/02#40", 1, Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY"),
                "other-source-ref"));
        assertThatThrownBy(() -> service.observe(actor, "missing-text", new MaterialProductService.ObservationInput(
                otherSource, "OBS-MISSING", "1", "", null))).isInstanceOf(IllegalArgumentException.class);
        var cross = service.observe(actor, "cross-observation", new MaterialProductService.ObservationInput(
                otherSource, "OBS-CROSS", "1", originalText, null));
        grant(grants, watermark, issuer, actor, org, "OBSERVATION", cross.resultId(), "READ", "DISPLAY");
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        int clues = count(db, "enf3_clue");
        assertThat(service.trialRule(actor, ruleId, cross.resultId()).outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(authority.update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?",
                otherSource)).isEqualTo(1);
        assertThatThrownBy(() -> service.trialRule(actor, ruleId, cross.resultId()))
                .isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='missing-text'",
                Integer.class)).isZero();
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        assertThat(count(db, "enf3_clue")).isEqualTo(clues);
        System.out.printf("ENF_V2_M02_DM8 cross=NOT_APPLICABLE missing=REJECTED "
                + "withdrawn=DENIED receipts=%d events=%d clues=%d%n", receipts, events, clues);
    }

    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String issuer,
                              String actor, String org, String type, String id, String action, String purpose) {
        watermark.change(() -> grants.issue(new ProductGrants.Scope(issuer, actor, org, "ENFORCEMENT",
                "ENF-V2-MATERIAL", "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1", type, id, 1, action, purpose),
                Instant.now().plusSeconds(3600), 0, "enf-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
