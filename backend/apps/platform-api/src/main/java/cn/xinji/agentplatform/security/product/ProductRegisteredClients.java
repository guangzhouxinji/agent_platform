package cn.xinji.agentplatform.security.product;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/** Invalid or unreviewed client registrations never become usable through the issuer. */
public final class ProductRegisteredClients implements RegisteredClientRepository {
    private final RegisteredClientRepository delegate;
    private final JdbcTemplate db;

    public ProductRegisteredClients(RegisteredClientRepository delegate, JdbcTemplate db) {
        this.delegate = delegate;
        this.db = db;
    }

    @Override public void save(RegisteredClient client) {
        validate(client);
        delegate.save(client);
    }

    @Override public RegisteredClient findById(String id) {
        return current(validate(delegate.findById(id)));
    }

    @Override public RegisteredClient findByClientId(String clientId) {
        return current(validate(delegate.findByClientId(clientId)));
    }

    private RegisteredClient current(RegisteredClient client) {
        if (client == null) return null;
        Integer active = db.queryForObject("SELECT COUNT(*) FROM id2_client_current "
                + "WHERE client_id=? AND registered_id=? AND status='ACTIVE' AND version=1",
                Integer.class, client.getClientId(), client.getId());
        return active != null && active == 1 ? client : null;
    }

    private static RegisteredClient validate(RegisteredClient client) {
        if (client == null) return null;
        if (!client.getAuthorizationGrantTypes().equals(java.util.Set.of(AuthorizationGrantType.AUTHORIZATION_CODE))
                || !client.getClientAuthenticationMethods().equals(java.util.Set.of(ClientAuthenticationMethod.NONE))
                || !client.getClientSettings().isRequireProofKey()
                || client.getRedirectUris().isEmpty()
                || client.getRedirectUris().stream().anyMatch(uri -> !uri.startsWith("https://")))
            throw new IllegalStateException("Client registration violates the product PKCE contract");
        return client;
    }
}
