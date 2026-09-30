package cn.xinji.agentplatform.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EnforcementDevRecoveryDm8Test {
    private record Checkpoint(String batch, String digest, long bytes, long epoch) { }
    private record Authority(long epoch, boolean withdrawn, long deleteEpoch) { }

    @Test void coldRestoredMaterialCannotUseIndependentWithdrawnOrMissingWatermark() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null
                && authorityPassword != null && !businessUrl.equals(authorityUrl), "Dedicated ENF instances required");
        String batch = "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1";
        Checkpoint checkpoint = checkpoint(businessUrl, businessPassword, batch);
        Authority current = authority(authorityUrl, authorityPassword, batch);
        byte[] original = Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/original-M-01.txt"));
        assertThat(checkpoint.batch()).isEqualTo(batch);
        assertThat(checkpoint.bytes()).isEqualTo(original.length).isEqualTo(317);
        assertThat(checkpoint.digest()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(original)));
        assertThat(checkpoint.epoch()).isEqualTo(1);
        assertThat(current.epoch()).isEqualTo(2);
        assertThat(current.deleteEpoch()).isEqualTo(2);
        assertThat(current.withdrawn()).isTrue();
        assertThat(canReturn(checkpoint, current)).isFalse();
        assertThat(canReturn(checkpoint, null)).isFalse();
        assertThat(canReturn(checkpoint, new Authority(0, false, 0))).isFalse();
        Authority unreachable;
        try { unreachable = authority("jdbc:dm://127.0.0.1:5299", authorityPassword, batch); }
        catch (SQLException unavailable) { unreachable = null; }
        assertThat(canReturn(checkpoint, unreachable)).isFalse();
        System.out.printf("ENF_RECOVERY_DM8 batch=%s businessEpoch=%d independentEpoch=%d "
                + "sourceWithdrawn=%s deleteEpoch=%d bytes=%d oldSnapshot=DENIED "
                + "authorityMissing=DENIED authorityRollback=DENIED%n", batch, checkpoint.epoch(),
                current.epoch(), current.withdrawn(), current.deleteEpoch(), original.length);
    }

    private static boolean canReturn(Checkpoint checkpoint, Authority current) {
        return current != null && !current.withdrawn() && current.epoch() == checkpoint.epoch()
                && current.deleteEpoch() <= checkpoint.epoch();
    }

    private static Checkpoint checkpoint(String url, String password, String batch) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.prepareStatement("SELECT BATCH_ID,MATERIAL_SHA,FILE_BYTES,"
                     + "CHECKPOINT_EPOCH FROM ENF_DEV_RECOVERY_PROBE WHERE BATCH_ID=?")) {
            statement.setString(1, batch);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Missing business checkpoint");
                var result = new Checkpoint(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4));
                if (rows.next()) throw new IllegalStateException("Ambiguous business checkpoint");
                return result;
            }
        }
    }

    private static Authority authority(String url, String password, String batch) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.prepareStatement("SELECT CURRENT_EPOCH,SOURCE_WITHDRAWN,DELETE_EPOCH "
                     + "FROM ENF_DEV_AUTHORITY_PROBE WHERE BATCH_ID=?")) {
            statement.setString(1, batch);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Missing independent watermark");
                var result = new Authority(rows.getLong(1), "Y".equals(rows.getString(2)), rows.getLong(3));
                if (rows.next()) throw new IllegalStateException("Ambiguous independent watermark");
                return result;
            }
        }
    }
}
