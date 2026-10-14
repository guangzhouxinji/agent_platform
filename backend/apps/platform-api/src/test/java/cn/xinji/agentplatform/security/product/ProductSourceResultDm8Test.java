package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductSourceResultDm8Test {
    @Test void isolatedDm8ResultMigrationAndReversePreservePredecessors() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "Isolated DM8 migration needs a configured database");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "SR" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        create(url, user, password, schema, secret);
        try (var connection = DriverManager.getConnection(url, schema, secret)) {
            List<String> predecessors = List.of("V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql",
                    "V036__product_source_candidates.sql", "V037__product_source_admission.sql",
                    "V038__product_task_source_scope.sql", "V039__product_source_verification.sql");
            try {
                for (String migration : predecessors) script(connection, migration);
                script(connection, "V040__product_source_results.sql");
                for (String migration : List.of("V041__product_task_authorization_version.sql",
                        "V042__product_consultation_goal.sql", "V043__product_task_cancel_epoch.sql",
                        "V045__product_task_execution_fence.sql")) script(connection, migration);
                script(connection, "V063__product_search_execution_binding.sql");
                script(connection, "V064__product_search_ranking_evidence.sql");
                script(connection, "V065__product_search_stable_cursor.sql");
                script(connection, "V067__product_search_semantic_index.sql");
                script(connection, "V068__product_search_hybrid_evidence.sql");
                script(connection, "V069__product_search_query_expansion.sql");
                script(connection, "V070__product_semantic_vector_snapshot_binding.sql");
                script(connection, "V071__product_search_rerank_evidence.sql");
                script(connection, "V072__product_search_metadata_filters.sql");
                script(connection, "V073__product_search_filter_evidence.sql");
                script(connection, "V074__product_search_confidence.sql");
                script(connection, "V079__product_search_digest_version.sql");
                script(connection, "V084__product_search_field_evidence.sql");
                for(String table:List.of("con4_search_field_scope","con4_result_field_evidence")) {
                    try(var command=connection.createStatement();var rows=command.executeQuery("SELECT COUNT(*) FROM "+table)) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                    }
                }
                script(connection, "V076__product_search_selection.sql");
                script(connection, "V077__product_execution_attempt_history.sql");
                script(connection, "V078__product_search_attempt_history_binding.sql");
                try (var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT COUNT(*) FROM con4_selection_revision")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
                try (var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT candidate_count FROM con4_result_set")) {
                    assertThat(rows.next()).isFalse();
                }
                try (var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT COUNT(*) FROM con4_search_attempt")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
                try (var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT page_size FROM con4_result_cursor")) {
                    assertThat(rows.next()).isFalse();
                }
                script(connection, "U084__product_search_field_evidence.sql");
                script(connection, "U076__product_search_selection.sql");
                script(connection, "U078__product_search_attempt_history_binding.sql");
                script(connection, "U077__product_execution_attempt_history.sql");
                script(connection, "U074__product_search_confidence.sql");
                script(connection, "U079__product_search_digest_version.sql");
                script(connection, "U073__product_search_filter_evidence.sql");
                script(connection, "U072__product_search_metadata_filters.sql");
                script(connection, "U071__product_search_rerank_evidence.sql");
                script(connection, "U070__product_semantic_vector_snapshot_binding.sql");
                script(connection, "U069__product_search_query_expansion.sql");
                script(connection, "U068__product_search_hybrid_evidence.sql");
                script(connection, "U067__product_search_semantic_index.sql");
                script(connection, "U065__product_search_stable_cursor.sql");
                script(connection, "U064__product_search_ranking_evidence.sql");
                script(connection, "U063__product_search_execution_binding.sql");
                script(connection, "U040__product_source_results.sql");
                try (var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT COUNT(*) FROM con4_source_verification")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            } finally {
                for (String table : List.of("con4_result_field_evidence","con4_search_field_scope",
                        "con4_selection_member", "con4_selection_revision",
                        "con4_document_vector_binding", "con4_vector_build", "con4_document_vector",
                        "con4_embedding_model", "con4_search_request", "con4_result_cursor",
                        "con4_result_member", "con4_result_set", "con4_search_source",
                        "con4_search_attempt", "con3_execution_checkpoint", "con3_execution_attempt", "con3_execution_lease",
                        "con3_command_receipt", "con3_search_input", "con3_goal_current",
                        "con3_goal_revision", "con3_message", "con3_session",
                        "con4_source_verification_request",
                        "con4_source_verification", "con4_task_source_request", "con4_task_source_event",
                        "con4_task_source_current", "con4_task_source_scope", "con4_source_authority_request",
                        "con4_source_authority_event", "con4_source_current", "con4_source_field_use",
                        "con4_source_admission", "con4_source_request", "con4_source_event",
                        "con4_source_contract", "con3_event", "con3_initial_receipt", "con3_initial_task",
                        "con3_data_scope", "con3_task", "con3_organization", "id2_grant_event",
                        "id2_current_epoch", "id2_grant", "id2_session", "id2_key_event",
                        "id2_signing_key", "id2_key_control", "id2_subject_event", "id2_subject"))
                    drop(connection, table);
            }
        } finally {
            remove(url, user, password, schema);
        }
    }

    private static void script(java.sql.Connection connection, String name) {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/" + name)));
    }

    private static void create(String url, String user, String password, String schema, String secret)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password);
             var command = connection.createStatement()) {
            command.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
            command.execute("GRANT RESOURCE TO " + schema);
        }
    }

    private static void remove(String url, String user, String password, String schema)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password);
             var command = connection.createStatement()) {
            command.execute("DROP USER " + schema);
        }
    }

    private static void drop(java.sql.Connection connection, String table) {
        try (var command = connection.createStatement()) { command.execute("DROP TABLE " + table); }
        catch (SQLException absent) { /* Partial migration can leave a table absent. */ }
    }
}
