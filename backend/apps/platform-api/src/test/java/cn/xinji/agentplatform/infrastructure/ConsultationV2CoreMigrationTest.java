package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConsultationV2CoreMigrationTest {
    @Test void purposeSelectionPinsExactInputAndReversesOnlyOwnedRelations() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V031__consultation_v2_dev_purpose_selection.sql"));
        String down = Files.readString(migrations.resolve("U031__consultation_v2_dev_purpose_selection.sql"));
        assertThat(up).contains("UNIQUE (task_id, input_revision, feature_seq, focus_seq)",
                "REFERENCES con2_feature_revision(task_id, feature_seq)",
                "REFERENCES con2_focus(task_id, seq)", "REFERENCES con2_result_member(set_id, ordinal)",
                "REFERENCES con2_receipt(receipt_id)", "TECHNICAL_SIMILARITY");
        assertThat(down).containsSubsequence("DROP TABLE con2_purpose_revision",
                "DROP TABLE con2_purpose_state");
    }
    @Test void correctionMigrationSeparatesOriginalUtteranceAndReplacement() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V030__consultation_v2_dev_corrections.sql"));
        String down = Files.readString(migrations.resolve("U030__consultation_v2_dev_corrections.sql"));
        assertThat(up).contains("utterance CLOB NOT NULL", "FOREIGN KEY (task_id, old_revision)",
                "FOREIGN KEY (task_id, new_revision)", "REFERENCES con2_receipt(receipt_id)",
                "new_revision = old_revision + 1");
        assertThat(up).doesNotContain("ALTER TABLE con2_message", "DROP TABLE con2_");
        assertThat(down).isEqualTo("DROP TABLE con2_correction;\n");
    }
    @Test void isolatedMigrationHasSevenOwnedTablesAndReverseRollback() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V023__consultation_v2_dev_task_core.sql"));
        String down = Files.readString(migrations.resolve("U023__consultation_v2_dev_task_core.sql"));
        assertThat(up.split("CREATE TABLE con2_", -1)).hasSize(8);
        assertThat(up).contains("UNIQUE (scope_sha, idem_sha)", "FOREIGN KEY (task_id, attempt_id)",
                "body CLOB", "ix_con2_task_retention", "delegation_until");
        assertThat(up).doesNotContain("ALTER TABLE con_", "DROP TABLE con_");
        assertThat(down).containsSubsequence("DROP TABLE con2_checkpoint", "DROP TABLE con2_lease",
                "DROP TABLE con2_attempt", "DROP TABLE con2_task");
    }

    @Test void textResultMigrationKeepsOldTablesAndReversesForeignKeys() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V024__consultation_v2_text_results.sql"));
        String down = Files.readString(migrations.resolve("U024__consultation_v2_text_results.sql"));
        assertThat(up.split("CREATE TABLE con2_", -1)).hasSize(5);
        assertThat(up).contains("UNIQUE (task_id, set_id)", "FOREIGN KEY (task_id, set_id)",
                "FOREIGN KEY (set_id, ordinal)", "REFERENCES con2_receipt(receipt_id)");
        assertThat(up).doesNotContain("ALTER TABLE con2_task", "DROP TABLE con2_");
        assertThat(down).containsSubsequence("DROP TABLE con2_focus", "DROP TABLE con2_result_member",
                "DROP TABLE con2_result_set", "DROP TABLE con2_result_state");
    }

    @Test void featureMigrationKeepsInputAndReceiptRelationshipsAndRollsBack() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V025__consultation_v2_feature_confirmations.sql"));
        String down = Files.readString(migrations.resolve("U025__consultation_v2_feature_confirmations.sql"));
        assertThat(up.split("CREATE TABLE con2_", -1)).hasSize(4);
        assertThat(up).contains("FOREIGN KEY (task_id, input_revision)",
                "REFERENCES con2_message(task_id, revision)", "UNIQUE (receipt_id)",
                "FOREIGN KEY (task_id, feature_seq)");
        assertThat(down).containsSubsequence("DROP TABLE con2_feature_member",
                "DROP TABLE con2_feature_revision", "DROP TABLE con2_feature_state");
    }

    @Test void humanRequestMigrationPinsInputAndReceiptWithoutChangingOldTables() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V026__consultation_v2_human_requests.sql"));
        String down = Files.readString(migrations.resolve("U026__consultation_v2_human_requests.sql"));
        assertThat(up.split("CREATE TABLE con2_", -1)).hasSize(3);
        assertThat(up).contains("FOREIGN KEY (task_id, input_revision)",
                "REFERENCES con2_message(task_id, revision)", "UNIQUE (receipt_id)",
                "CHECK (request_seq > 0)", "FOREIGN KEY (receipt_id) REFERENCES con2_receipt(receipt_id)");
        assertThat(up).doesNotContain("ALTER TABLE con2_", "DROP TABLE con2_");
        assertThat(down).containsSubsequence("DROP TABLE con2_human_request;",
                "DROP TABLE con2_human_request_state;");
    }

    @Test void humanAcceptanceMigrationKeepsExactRequestAndReceiptRelationships() throws Exception {
        Path migrations = WorkspaceTestPaths.root().resolve(Path.of("db", "dm8"));
        String up = Files.readString(migrations.resolve("V027__consultation_v2_human_acceptance.sql"));
        String down = Files.readString(migrations.resolve("U027__consultation_v2_human_acceptance.sql"));
        assertThat(up.split("CREATE TABLE con2_", -1)).hasSize(2);
        assertThat(up).contains("PRIMARY KEY (task_id, request_seq)",
                "REFERENCES con2_human_request(task_id, request_seq)",
                "REFERENCES con2_receipt(receipt_id)", "UNIQUE (request_id)", "UNIQUE (receipt_id)");
        assertThat(up).doesNotContain("ALTER TABLE con2_", "DROP TABLE con2_");
        assertThat(down).contains("DROP TABLE con2_human_acceptance;");
    }
}
