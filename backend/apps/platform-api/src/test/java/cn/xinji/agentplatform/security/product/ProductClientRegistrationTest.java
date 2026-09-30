package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

class ProductClientRegistrationTest {
    @Test void exactPublicClientCanBeRegisteredReplayedAndRevokedWithoutDeletingHistory() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product-client-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql", "V033__product_oauth2.sql",
                    "V057__product_oauth_client_control.sql"})
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8", name)));
        }
        var db = new JdbcTemplate(source);
        var clients = new ProductRegisteredClients(new JdbcRegisteredClientRepository(db), db);
        var service = new ProductClients(db, clients,
                new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
        var input = new ProductClients.Input("consultation-workspace", "Consultation workspace",
                "https://localhost:5443/auth/callback");
        assertThatThrownBy(() -> service.register(new ProductClients.Input(input.clientId(),
                input.name(), "http://localhost/callback"), "invalid-client", "maintainer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(clients.findByClientId(input.clientId())).isNull();
        var registered = service.register(input, "register-client", "maintainer");
        assertThat(registered.status()).isEqualTo("ACTIVE");
        assertThat(service.register(input, "register-client", "maintainer")).isEqualTo(registered);
        assertThatThrownBy(() -> service.register(new ProductClients.Input(input.clientId(),
                input.name(), "https://localhost/other"), "register-client", "maintainer"))
                .isInstanceOf(IllegalStateException.class);
        var client = clients.findByClientId(input.clientId());
        assertThat(client).isNotNull();
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getRedirectUris()).containsExactly(input.redirectUri());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_client_event", Integer.class)).isEqualTo(1);
        var revoked = service.revoke(input.clientId(), 1, "revoke-client", "maintainer");
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(service.revoke(input.clientId(), 1, "revoke-client", "maintainer"))
                .isEqualTo(revoked);
        assertThat(clients.findByClientId(input.clientId())).isNull();
        assertThatThrownBy(() -> service.register(input, "register-client", "maintainer"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM oauth2_registered_client",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_client_event", Integer.class)).isEqualTo(2);
    }
}
