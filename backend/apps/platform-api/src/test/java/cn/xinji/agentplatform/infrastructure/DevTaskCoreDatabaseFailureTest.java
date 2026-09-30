package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTextResultStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevFeatureConfirmationStore;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DevTaskCoreDatabaseFailureTest {
    @Test void featureConfirmationReturns503WithoutSchemaOrFallback() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:v2_feature_unavailable;MODE=Oracle", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        var store = new Dm8DevFeatureConfirmationStore(source, new DataSourceTransactionManager(source),
                ZoneId.of("Asia/Shanghai"));
        var actor = new DevTaskCoreStore.Actor("dev-con-a", "dev-con-isolated", 1,
                Instant.parse("2099-01-01T00:00:00Z"));
        String task = "00000000-0000-0000-0000-000000000001";
        assertThatThrownBy(() -> store.confirm(actor, task, "feature-db-down-123", 2, 0,
                "DEV-CON-FEATURE-CANDIDATES-01", java.util.List.of("F1", "F2", "F3"),
                permissiveGuard(), (human, manifestId, display, now) -> null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("DATABASE_UNAVAILABLE");
        assertThatThrownBy(() -> store.read(actor, task, 1, permissiveGuard(),
                (human, manifestId, display, now) -> null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("DATABASE_UNAVAILABLE");
    }

    @Test void evidenceReadReturns503WhenDatabaseIsUnavailable() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:v2_evidence_unavailable;MODE=Oracle", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        var store = new Dm8DevTextResultStore(source, new DataSourceTransactionManager(source),
                ZoneId.of("Asia/Shanghai"));
        var actor = new DevTaskCoreStore.Actor("dev-con-a", "dev-con-isolated", 1,
                Instant.parse("2099-01-01T00:00:00Z"));
        assertThatThrownBy(() -> store.prepareEvidence(actor, "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002", 1, permissiveGuard(),
                (human, id, version, digest, query, save, display, now) -> null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("DATABASE_UNAVAILABLE");
    }

    @Test void missingDatabaseSchemaReturns503WithoutAnInMemoryFallback() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:v2_unavailable;MODE=Oracle", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        var store = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source),
                ZoneId.of("Asia/Shanghai"));
        var actor = new DevTaskCoreStore.Actor("dev-con-a", "dev-con-isolated", 1,
                Instant.parse("2099-01-01T00:00:00Z"));
        assertThatThrownBy(() -> store.create(actor, "missing-db-12345", "synthetic", permissiveGuard()))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("DATABASE_UNAVAILABLE");
    }

    private static DevTaskCoreStore.Guard permissiveGuard() {
        return new DevTaskCoreStore.Guard() {
                    @Override public boolean humanCurrent(DevTaskCoreStore.Actor subject, Instant now) { return true; }
                    @Override public boolean inputAllowed(DevTaskCoreStore.Actor subject, String kind,
                                                           String text, Instant now) { return true; }
                    @Override public boolean inputDisplayAllowed(DevTaskCoreStore.Actor subject, String kind,
                                                                  String text, Instant now) { return true; }
                    @Override public boolean delegationCurrent(String owner, String org, long version,
                                                               Instant until, Instant now) { return false; }
                    @Override public boolean serviceCurrent(DevTaskCoreStore.Actor subject,
                            DevTaskCoreStore.ServiceRole role, Instant now) { return false; }
                };
    }
}
