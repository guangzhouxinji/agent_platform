package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialDeletionDm8Scenarios {
    private MaterialDeletionDm8Scenarios() { }

    static void expiredMaterial(JdbcTemplate db, Dm8MaterialRepository repository,
                                MaterialAccess access, String actor, String materialId,
                                Path root) throws Exception {
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        long files;
        try (var paths = Files.list(root)) { files = paths.count(); }
        var expired = new MaterialProductService(repository, new LocalMaterialFiles(root), access,
                Clock.offset(Clock.systemUTC(), Duration.ofHours(2)));
        assertThatThrownBy(() -> expired.preview(actor, materialId)).isInstanceOf(SecurityException.class)
                .hasMessageContaining("Material use unavailable");
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(files); }
        System.out.printf("ENF_V2_RETENTION_DM8 expired=DENIED receipts=%d events=%d files=%d%n",
                receipts, events, files);
    }

    static void run(JdbcTemplate db, JdbcTemplate authority, MaterialProductService service,
                    String actor, String sourceId, MaterialProductService.MaterialInput input,
                    Path root) throws Exception {
        assertThat(authority.queryForObject("SELECT state FROM enf3_source_watermark WHERE source_id=?",
                String.class, sourceId)).isEqualTo("READY");
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        int materials = count(db, "enf3_material");
        long files;
        try (var paths = Files.list(root)) { files = paths.count(); }
        assertThat(authority.update("UPDATE enf3_delete_watermark SET state='DELETED',"
                + "delete_epoch=delete_epoch+1 WHERE source_id=? AND state='READY'", sourceId)).isEqualTo(1);
        assertThatThrownBy(() -> service.registerMaterial(actor, "material-1", input))
                .isInstanceOf(SecurityException.class);
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        assertThat(count(db, "enf3_material")).isEqualTo(materials);
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(files); }
        System.out.printf("ENF_V2_DELETE_DM8 source=READY delete=DELETED oldReceipt=DENIED "
                + "receipts=%d events=%d materials=%d files=%d%n", receipts, events, materials, files);
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
