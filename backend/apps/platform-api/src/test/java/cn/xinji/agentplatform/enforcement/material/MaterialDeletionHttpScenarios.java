package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialDeletionHttpScenarios {
    private MaterialDeletionHttpScenarios() { }

    static void oldReceiptCannotReviveDeletedSourceMaterial(HttpClient http, String base, String token,
                                                              String materialId, String materialBody,
                                                              JdbcTemplate db, JdbcTemplate authority,
                                                              String sourceId, String fileKey) throws Exception {
        String url = base + "/v2/enforcement/materials/" + materialId;
        var read = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET().build();
        assertThat(http.send(read, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        int materials = count(db, "enf3_material");
        authority.update("UPDATE enf3_delete_watermark SET state='DELETED',delete_epoch=delete_epoch+1 "
                + "WHERE source_id=? AND state='READY'", sourceId);
        var denied = http.send(read, HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain(materialId);
        var replay = HttpRequest.newBuilder(URI.create(base + "/v2/enforcement/materials"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Idempotency-Key", "material-http")
                .POST(HttpRequest.BodyPublishers.ofString(materialBody)).build();
        assertThat(http.send(replay, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        assertThat(count(db, "enf3_material")).isEqualTo(materials);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material WHERE file_key=?", Integer.class,
                fileKey)).isEqualTo(1);
        System.out.printf("ENF_V2_DELETE_HTTP oldReceipt=DENIED read=403 receipts=%d events=%d materials=%d%n",
                receipts, events, materials);
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
