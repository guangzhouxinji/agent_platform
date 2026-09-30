package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialSourceConfigurationDm8Scenarios {
    private MaterialSourceConfigurationDm8Scenarios() { }

    static void run(MaterialRepository repository, MaterialProductService service, JdbcTemplate db,
                    MaterialSourceSync sync, String actor, String sourceId) {
        assertThat(service.readSource(actor, sourceId).configVersion()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE source_id=? "
                + "AND config_version=1", Integer.class, sourceId)).isGreaterThan(0);
        var receipt = service.configureSource(actor, "source-config-v2", sourceId,
                new MaterialProductService.SourceConfigurationInput(1,
                        MaterialPageHttpsSource.CREDENTIAL_REF, true));
        assertThat(receipt.resultVersion()).isEqualTo(2);
        assertThat(service.readSource(actor, sourceId).configVersion()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_source_config_revision "
                + "WHERE source_id=? AND config_version IN (1,2)", Integer.class, sourceId)).isEqualTo(2);
        int receipts = db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class);
        int events = db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class);
        assertThatThrownBy(() -> service.configureSource(actor, "source-config-stale", sourceId,
                new MaterialProductService.SourceConfigurationInput(1,
                        MaterialPageHttpsSource.CREDENTIAL_REF, true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class)).isEqualTo(receipts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class)).isEqualTo(events);
        assertThat(sync.onePage(actor, sourceId).state()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE source_id=? "
                + "AND config_version=2 AND state='SUCCEEDED'", Integer.class, sourceId)).isEqualTo(1);
        assertThat(repository.syncCursor(sourceId)).isEqualTo("p2-3");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_observation", Integer.class)).isEqualTo(2);
        System.out.println("ENF_V2_M01_CONFIG_DM8 revision=1->2 stale=CONFLICT "
                + "attemptVersion=2 cursor=p2-3 observations=2 forbiddenReceipt=0");
    }
}
