package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

class ProductTaskCreationServiceTest {
    private static final String ISSUER = "https://identity.example.org";

    @Test void currentOrganizationGrantCreatesTaskAndReplayFailsClosed() throws Exception {
        var fixture = new ProductTaskCreationFixture();
        var db = fixture.db; var now = fixture.now; var expires = fixture.expires; var jwt = fixture.jwt;
        var watermark = fixture.watermark; var grants = fixture.grants; var directory = fixture.directory;
        var service = fixture.service; var input = fixture.input;
        assertThatThrownBy(() -> service.create(jwt, "no-grant", input)).isInstanceOf(ResponseStatusException.class);
        var createGrant = fixture.grant();
        var result = service.create(jwt, "create-1", input);
        assertThat(result).isEqualTo(service.create(jwt, "create-1", input));
        assertThat(directory.find(result.taskId()).orElseThrow().dataScope()).isEqualTo(result.dataScope());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task_create_receipt", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_event WHERE operation='TASK_CREATED'",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.create(jwt, "create-1", new ProductTaskCreationService.Input(
                "org", now.plusSeconds(1700), "approved", 1))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(jwt, "cross", new ProductTaskCreationService.Input(
                "elsewhere", expires, "approved", 1))).isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE id2_subject SET status='DISABLED' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThatThrownBy(() -> service.create(jwt, "create-1", input))
                .isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE id2_subject SET status='ACTIVE' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThat(service.create(jwt, "create-1", input)).isEqualTo(result);
        assertThatThrownBy(() -> service.create(jwt, "too-long", new ProductTaskCreationService.Input(
                "org", now.plusSeconds(4000), "approved", 1)))
                .isInstanceOf(ResponseStatusException.class);
        watermark.change(() -> grants.revoke(createGrant.grantId(), 1, "maintainer", "revoke-org", "c".repeat(64)));
        assertThatThrownBy(() -> service.create(jwt, "create-1", input))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task", Integer.class)).isEqualTo(1);
    }

    @Test void emptySchemaCanReverseTheMigration() throws Exception {
        JdbcDataSource primary = source();
        script(primary, "db/dm8/V032__product_identity.sql");
        script(primary, "db/dm8/V034__product_consultation_initial_task.sql");
        script(primary, "db/dm8/V041__product_task_authorization_version.sql");
        script(primary, "db/dm8/V058__product_task_organization_create.sql");
        script(primary, "db/dm8/U058__product_task_organization_create.sql");
    }

    static JdbcDataSource source() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
