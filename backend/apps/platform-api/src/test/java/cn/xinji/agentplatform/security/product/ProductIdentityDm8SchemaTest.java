package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;

class ProductIdentityDm8SchemaTest {
    @Test void forwardConstraintsAndReverseInIsolatedDm8Schema() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && admin != null && password != null,
                "Isolated DM8 credentials are not configured");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "ID" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean created = false;
        try {
            try (var control = DriverManager.getConnection(url, admin, password);
                 var create = control.createStatement()) {
                create.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
                created = true;
                create.execute("GRANT RESOURCE TO " + schema);
            }
            try (var db = DriverManager.getConnection(url, schema, secret)) {
                try {
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/V032__product_identity.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/V033__product_oauth2.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/V057__product_oauth_client_control.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/V034__product_consultation_initial_task.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/V041__product_task_authorization_version.sql")));
                    DriverManagerDataSource source = new DriverManagerDataSource(url, schema, secret);
                    var jdbc = new JdbcTemplate(source);
                    var clients = new ProductRegisteredClients(new JdbcRegisteredClientRepository(jdbc), jdbc);
                    var client = RegisteredClient.withId("isolated-db-probe")
                            .clientId("isolated-db-probe")
                            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri("https://client.example.org/callback")
                            .scope("openid")
                            .clientSettings(org.springframework.security.oauth2.server.authorization.settings
                                    .ClientSettings.builder().requireProofKey(true).build())
                            .build();
                    clients.save(client);
                    jdbc.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,"
                            + "version,created_at) VALUES(?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                            client.getClientId(), client.getId(), "https://client.example.org/callback");
                    assertThat(clients.findByClientId("isolated-db-probe").getId())
                            .isEqualTo("isolated-db-probe");
                    var authorizations = new JdbcOAuth2AuthorizationService(new JdbcTemplate(source), clients);
                    var authorization = OAuth2Authorization.withRegisteredClient(client)
                            .principalName("isolated-schema-probe")
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .token(new OAuth2AuthorizationCode("isolated-code-opaque-value", Instant.now(),
                                    Instant.now().plusSeconds(300)))
                            .build();
                    authorizations.save(authorization);
                    assertThat(authorizations.findById(authorization.getId()).getToken(OAuth2AuthorizationCode.class)
                            .getToken().getTokenValue()).isEqualTo("isolated-code-opaque-value");
                    var productAuthorizations = new ProductAuthorizationService(authorizations,
                            new JdbcTemplate(source), new TransactionTemplate(
                                    new DataSourceTransactionManager(source)), "https://issuer.example.org");
                    assertThat(productAuthorizations.findByToken("isolated-code-opaque-value",
                            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"))
                            .getId()).isEqualTo(authorization.getId());
                    assertThatThrownBy(() -> {
                        try (var insert = db.createStatement()) {
                            insert.execute("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,"
                                    + "status,credential_version,created_at,disabled_at) VALUES "
                                    + "('https://issuer.example.org','subject','login','hash','UNKNOWN',1,"
                                    + "CURRENT_TIMESTAMP,NULL)");
                        }
                    }).isInstanceOf(SQLException.class);
                    try (var tables = db.createStatement();
                         var rows = tables.executeQuery("SELECT COUNT(*) FROM USER_TABLES "
                                 + "WHERE TABLE_NAME LIKE 'ID2_%'")) {
                        rows.next();
                        assertThat(rows.getInt(1)).isEqualTo(12);
                    }
                    try (var tables = db.createStatement();
                         var rows = tables.executeQuery("SELECT COUNT(*) FROM USER_TABLES "
                                 + "WHERE TABLE_NAME LIKE 'CON3_%'")) {
                        rows.next();
                        assertThat(rows.getInt(1)).isEqualTo(6);
                    }
                    var primary = new JdbcTemplate(source);
                    var authoritySource = new JdbcDataSource();
                    authoritySource.setURL("jdbc:h2:mem:dm8-task-authority-" + schema + ";DB_CLOSE_DELAY=-1");
                    try (var authorityConnection = authoritySource.getConnection()) {
                        ScriptUtils.executeSqlScript(authorityConnection, new FileSystemResource(
                                Path.of("db/identity-authority/V001__grant_watermark.sql")));
                    }
                    var authority = new JdbcTemplate(authoritySource);
                    String issuer = "https://isolated-issuer.example.org";
                    authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                            + "VALUES(?,0,'READY',NULL,?)", issuer,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                    primary.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                            + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                            issuer, "isolated-member", "isolated-member", "encoded", "ACTIVE", 1);
                    var watermark = new ProductGrantWatermark(primary, authority, issuer);
                    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
                    var directory = new JdbcProductTaskDirectory(primary, Clock.systemUTC());
                    var grants = new ProductGrants(primary, transaction, Clock.systemUTC());
                    var tasks = new ProductInitialTaskService(directory, grants, watermark, transaction,
                            new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), issuer,
                            new ProductRetentionPolicy("isolated-retention", 1, "a".repeat(64),
                                    Duration.ofHours(2)));
                    var input = new ProductInitialTaskService.Input("isolated-organization", "Isolated organization",
                            "isolated-member", "isolated-retention", 1, Instant.now().plusSeconds(3600));
                    var task = tasks.create(input, "dm8-initial", "isolated-maintainer", "isolated-organization");
                    assertThat(tasks.create(input, "dm8-initial", "isolated-maintainer", "isolated-organization"))
                            .isEqualTo(task);
                    assertThat(directory.find(task.taskId()).orElseThrow().dataScope()).isEqualTo(task.dataScope());
                    assertThatThrownBy(() -> tasks.create(input, "dm8-another", "isolated-maintainer",
                            "isolated-organization")).isInstanceOf(IllegalStateException.class);
                    watermark.change(() -> grants.revoke(task.grantId(), 1, "isolated-maintainer",
                            "dm8-revoke", "d".repeat(64)));
                    assertThatThrownBy(() -> tasks.create(input, "dm8-initial", "isolated-maintainer",
                            "isolated-organization")).isInstanceOf(IllegalStateException.class);
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/U041__product_task_authorization_version.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/U034__product_consultation_initial_task.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/U057__product_oauth_client_control.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/U033__product_oauth2.sql")));
                    ScriptUtils.executeSqlScript(db, new FileSystemResource(
                            Path.of("db/dm8/U032__product_identity.sql")));
                    try (var tables = db.createStatement();
                         var rows = tables.executeQuery("SELECT COUNT(*) FROM USER_TABLES "
                                 + "WHERE TABLE_NAME LIKE 'ID2_%'")) {
                        rows.next();
                        assertThat(rows.getInt(1)).isZero();
                    }
                } finally {
                    for (String table : new String[] {"con3_event", "con3_initial_receipt", "con3_initial_task",
                            "con3_data_scope", "con3_task", "con3_organization",
                            "oauth2_authorization_consent", "oauth2_authorization",
                            "oauth2_registered_client", "id2_client_request", "id2_client_event",
                            "id2_client_current", "id2_grant_event", "id2_current_epoch", "id2_grant",
                            "id2_session", "id2_key_event", "id2_signing_key", "id2_key_control", "id2_subject_event",
                            "id2_subject"}) {
                        try (var drop = db.createStatement()) { drop.execute("DROP TABLE " + table + " CASCADE"); }
                        catch (SQLException absent) { /* Partial migration leaves some tables absent. */ }
                    }
                }
            }
        } finally {
            if (created) {
                try (var control = DriverManager.getConnection(url, admin, password);
                     var drop = control.createStatement()) {
                    drop.execute("DROP USER " + schema);
                }
            }
        }
    }
}
