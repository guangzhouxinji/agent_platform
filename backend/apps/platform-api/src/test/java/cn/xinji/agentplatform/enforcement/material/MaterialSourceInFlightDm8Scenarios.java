package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialSourceInFlightDm8Scenarios {
    private MaterialSourceInFlightDm8Scenarios() { }

    static void run(MaterialPageHttpsSource server, MaterialSourceSync sync, MaterialProductService service,
                    MaterialRepository repository, JdbcTemplate db, String actor, String sourceId) throws Exception {
        int observations = count(db, "enf3_observation");
        int events = count(db, "enf3_event");
        int attempts = count(db, "enf3_sync_attempt");
        server.pauseNextEmptyPage();
        var inFlight = CompletableFuture.supplyAsync(() -> sync.onePage(actor, sourceId));
        try {
            assertThat(server.awaitPausedPage()).isTrue();
            assertThat(service.configureSource(actor, "source-stop-v3", sourceId,
                    new MaterialProductService.SourceConfigurationInput(2,
                            MaterialPageHttpsSource.CREDENTIAL_REF, false)).resultVersion()).isEqualTo(3);
        } finally { server.resumePage(); }
        assertThatThrownBy(inFlight::join).hasRootCauseInstanceOf(MaterialPageSource.Failure.class)
                .hasRootCauseMessage("Source disabled");
        assertThat(repository.syncCursor(sourceId)).isEqualTo("p2-3");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE source_id=? "
                + "AND config_version=2 AND state='FAILED' AND failure_code='SOURCE_DISABLED'",
                Integer.class, sourceId)).isEqualTo(1);
        assertThat(count(db, "enf3_observation")).isEqualTo(observations);
        assertThat(count(db, "enf3_event")).isEqualTo(events + 1);
        assertThat(count(db, "enf3_sync_attempt")).isEqualTo(attempts + 1);
        assertThatThrownBy(() -> sync.onePage(actor, sourceId))
                .isInstanceOf(MaterialPageSource.Failure.class).hasMessage("Source disabled");
        assertThat(count(db, "enf3_sync_attempt")).isEqualTo(attempts + 1);
        assertThat(service.configureSource(actor, "source-resume-v4", sourceId,
                new MaterialProductService.SourceConfigurationInput(3,
                        MaterialPageHttpsSource.CREDENTIAL_REF, true)).resultVersion()).isEqualTo(4);
        System.out.println("ENF_V2_M01_STOP_DM8 inFlight=SOURCE_DISABLED cursor=p2-3 "
                + "attemptVersion=2 next=DENIED observations=UNCHANGED forbiddenReceipt=0");
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
