package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DevHumanReturnMigrationTest {
    @Test void returnPinsExactAcceptanceAndReceiptWithoutErasingOpinionOrAcceptance() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V029__consultation_v2_human_returns.sql"));
        String down = Files.readString(migrations.resolve("U029__consultation_v2_human_returns.sql"));
        assertThat(up.split("CREATE TABLE con2_", -1)).hasSize(2);
        assertThat(up).contains("PRIMARY KEY (task_id, request_seq)",
                "REFERENCES con2_human_acceptance(task_id, request_seq)",
                "REFERENCES con2_receipt(receipt_id)", "UNIQUE (return_id)", "UNIQUE (receipt_id)");
        assertThat(up).doesNotContain("ALTER TABLE con2_", "DROP TABLE con2_", "CLOB");
        assertThat(down).contains("DROP TABLE con2_human_return;");
    }
}
