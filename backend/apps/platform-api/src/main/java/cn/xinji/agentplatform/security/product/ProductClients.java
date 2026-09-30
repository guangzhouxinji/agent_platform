package cn.xinji.agentplatform.security.product;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.transaction.support.TransactionTemplate;

/** Accountable provisioning of public PKCE clients; registration never grants business access. */
public final class ProductClients {
    public record Input(String clientId, String name, String redirectUri) { }
    public record Receipt(String clientId, long version, String status) { }
    private record Replay(String hash, String clientId, String operation) { }

    private final JdbcTemplate db;
    private final RegisteredClientRepository clients;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ProductClients(JdbcTemplate db, RegisteredClientRepository clients,
            TransactionTemplate transactions, Clock clock) {
        this.db = db;
        this.clients = clients;
        this.transactions = transactions;
        this.clock = clock;
    }

    Receipt register(Input input, String requestId, String actor) {
        if (input == null || !id(input.clientId()) || input.name() == null
                || input.name().isBlank() || input.name().length() > 200
                || !redirect(input.redirectUri()) || !id(requestId) || blank(actor))
            throw new IllegalArgumentException("Exact public client registration required");
        String hash = sha("REGISTER", actor, input.clientId(), input.name(), input.redirectUri());
        return transactions.execute(status -> {
            Receipt previous = replay(requestId, hash, "REGISTER");
            if (previous != null) return previous;
            Integer existing = db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=?",
                    Integer.class, input.clientId());
            if (existing != null && existing != 0)
                throw new IllegalStateException("Client ID is already registered");
            String registeredId = UUID.randomUUID().toString();
            clients.save(RegisteredClient.withId(registeredId)
                    .clientId(input.clientId()).clientName(input.name())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(input.redirectUri()).scope("openid")
                    .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                    .build());
            Timestamp now = Timestamp.from(clock.instant());
            db.update("INSERT INTO id2_client_current(client_id,registered_id,redirect_uri,status,version,"
                    + "created_at) VALUES(?,?,?,'ACTIVE',1,?)", input.clientId(), registeredId,
                    input.redirectUri(), now);
            event(input.clientId(), "REGISTER", actor, hash, 0, 1, requestId, now);
            return new Receipt(input.clientId(), 1, "ACTIVE");
        });
    }

    Receipt revoke(String clientId, long expectedVersion, String requestId, String actor) {
        if (!id(clientId) || expectedVersion != 1 || !id(requestId) || blank(actor))
            throw new IllegalArgumentException("Exact client revocation required");
        String hash = sha("REVOKE", actor, clientId, Long.toString(expectedVersion));
        return transactions.execute(status -> {
            Receipt previous = replay(requestId, hash, "REVOKE");
            if (previous != null) return previous;
            if (db.update("UPDATE id2_client_current SET status='REVOKED',version=2 "
                    + "WHERE client_id=? AND status='ACTIVE' AND version=?", clientId,
                    expectedVersion) != 1) throw new IllegalStateException("Client version changed");
            db.update("UPDATE id2_session SET status='REVOKED' WHERE client_id=? AND status='ACTIVE'",
                    clientId);
            event(clientId, "REVOKE", actor, hash, 1, 2, requestId,
                    Timestamp.from(clock.instant()));
            return new Receipt(clientId, 2, "REVOKED");
        });
    }

    private Receipt replay(String requestId, String hash, String operation) {
        List<Replay> rows = db.query("SELECT r.request_sha,e.client_id,e.operation FROM id2_client_request r "
                + "JOIN id2_client_event e ON e.event_id=r.event_id WHERE r.request_id=?",
                (rs, row) -> new Replay(rs.getString(1), rs.getString(2), rs.getString(3)), requestId);
        if (rows.isEmpty()) return null;
        Replay prior = rows.getFirst();
        if (!prior.hash().equals(hash) || !prior.operation().equals(operation))
            throw new IllegalStateException("Client request conflicts with history");
        List<Receipt> current = db.query("SELECT version,status FROM id2_client_current WHERE client_id=?",
                (rs, row) -> new Receipt(prior.clientId(), rs.getLong(1), rs.getString(2)),
                prior.clientId());
        if (current.size() != 1 || !current.getFirst().status().equals(
                "REGISTER".equals(operation) ? "ACTIVE" : "REVOKED"))
            throw new IllegalStateException("Client request is no longer current");
        return current.getFirst();
    }

    private void event(String clientId, String operation, String actor, String hash,
            long before, long after, String requestId, Timestamp now) {
        String eventId = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_client_event(event_id,client_id,operation,actor_ref,request_sha,"
                + "before_version,after_version,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                eventId, clientId, operation, actor, hash, before, after, now);
        db.update("INSERT INTO id2_client_request(request_id,request_sha,event_id) VALUES(?,?,?)",
                requestId, hash, eventId);
    }

    private static boolean redirect(String value) {
        if (value == null || value.length() > 1000) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean id(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String sha(String... fields) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields)
                hash.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
