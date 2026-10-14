package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** One independently registered, auditable maintenance principal; no header supplies identity. */
public final class ProductMaintenanceProof {
    public enum Role { SUBJECTS, GRANTS, KEYS, SOURCES, HUMAN_RETENTION }
    private record Maintainer(String subject, String organization, String fingerprint, String roles,
                              String status, long version) { }

    private final JdbcTemplate authority;
    private final TransactionTemplate transactions;

    public ProductMaintenanceProof(JdbcTemplate authority, TransactionTemplate transactions) {
        this.authority = authority;
        this.transactions = transactions;
        Maintainer registered = current();
        if (registered == null || !"ACTIVE".equals(registered.status())
                || registered.subject() == null || registered.subject().isBlank()
                || registered.organization() == null || registered.organization().isBlank()
                || registered.fingerprint() == null || !registered.fingerprint().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Accountable maintainer is not registered");
    }

    public String require(HttpServletRequest request, Role role) {
        if (role == Role.HUMAN_RETENTION)
            throw new IllegalStateException("Human retention requires a locked maintenance lease");
        String fingerprint = fingerprint(request);
        Maintainer current = current();
        boolean allowed = current != null && "ACTIVE".equals(current.status())
                && fingerprint != null && current.fingerprint().equals(fingerprint)
                && List.of(current.roles().split(",")).contains(role.name());
        audit(current == null ? null : current.subject(), fingerprint, role.name(), allowed ? "ALLOW" : "DENY");
        if (!allowed) throw denied();
        return current.subject() + "#" + fingerprint;
    }

    public String requireOrganization(HttpServletRequest request, Role role) {
        require(request, role);
        return current().organization();
    }

    /** The parent must settle its main commit inside the callback; this component only holds identity's row lock. */
    public <T> T withRetentionProof(HttpServletRequest request, Function<RetentionLease, T> operation) {
        Objects.requireNonNull(operation);
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || authority.getDataSource() == null || manager.getDataSource() != authority.getDataSource()
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())
            throw new IllegalStateException("Standalone matching authority transaction required");
        X509Certificate certificate = retentionCertificate(request);
        // Own this transaction independently of any mutable caller-supplied template settings.
        return new TransactionTemplate(manager).execute(status -> {
            Maintainer registered = current(true);
            requireRetention(registered, certificate);
            Object resource = TransactionSynchronizationManager.getResource(authority.getDataSource());
            if (!(resource instanceof ConnectionHolder holder)) throw denied();
            var lease = new RetentionLease(this, manager, registered, certificate, holder, holder.getConnection());
            try {
                lease.requireCurrent();
                T result = operation.apply(lease);
                lease.requireCurrent();
                return result;
            } finally {
                lease.active = false;
            }
        });
    }

    public static final class RetentionLease {
        private final ProductMaintenanceProof owner;
        private final DataSourceTransactionManager manager;
        private final Maintainer expected;
        private final X509Certificate certificate;
        private final ConnectionHolder holder;
        private final Connection connection;
        private final Thread thread = Thread.currentThread();
        private boolean active = true;

        private RetentionLease(ProductMaintenanceProof owner, DataSourceTransactionManager manager,
                Maintainer expected, X509Certificate certificate, ConnectionHolder holder, Connection connection) {
            this.owner = owner; this.manager = manager; this.expected = expected;
            this.certificate = certificate; this.holder = holder; this.connection = connection;
        }

        public void requireCurrent() {
            if (!active || thread != Thread.currentThread()
                    || manager.getDataSource() != owner.authority.getDataSource()
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.getResource(owner.authority.getDataSource()) != holder
                    || holder.getConnection() != connection)
                throw new IllegalStateException("Current owned retention lease required");
            try {
                if (connection.isClosed() || connection.getAutoCommit()) throw denied();
            } catch (java.sql.SQLException unavailable) {
                throw new IllegalStateException("Retention transaction unavailable", unavailable);
            }
            Maintainer current = owner.current();
            requireRetention(current, certificate);
            if (!expected.equals(current)) throw denied();
        }

        public String operator() { requireCurrent(); return expected.subject() + "#" + expected.fingerprint(); }
        public String subject() { requireCurrent(); return expected.subject(); }
        public String organization() { requireCurrent(); return expected.organization(); }
        public String certificateSha() { requireCurrent(); return expected.fingerprint(); }
        public long version() { requireCurrent(); return expected.version(); }
        public Instant certificateExpiresAt() { requireCurrent(); return certificate.getNotAfter().toInstant(); }
    }

    private static X509Certificate retentionCertificate(HttpServletRequest request) {
        if (request == null || !request.isSecure()) throw denied();
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0 || chain[0] == null)
            throw denied();
        return chain[0];
    }

    private static void requireRetention(Maintainer registered, X509Certificate certificate) {
        try {
            certificate.checkValidity();
            Instant now = Instant.now();
            if (certificate.getNotBefore() == null || certificate.getNotAfter() == null
                    || now.isBefore(certificate.getNotBefore().toInstant())
                    || !now.isBefore(certificate.getNotAfter().toInstant())) throw denied();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (registered == null || !"ACTIVE".equals(registered.status()) || registered.version() < 1
                    || registered.subject() == null || registered.subject().isBlank()
                    || registered.organization() == null || registered.organization().isBlank()
                    || !sha.equals(registered.fingerprint()) || registered.roles() == null
                    || !List.of(registered.roles().split(",")).contains(Role.HUMAN_RETENTION.name())) throw denied();
        } catch (ResponseStatusException refused) {
            throw refused;
        } catch (Exception invalid) {
            throw denied();
        }
    }

    public void revoke(HttpServletRequest request, long expectedVersion) {
        String actor = require(request, Role.KEYS);
        transactions.executeWithoutResult(status -> {
            Maintainer current = current();
            if (current == null || !"ACTIVE".equals(current.status()) || current.version() != expectedVersion
                    || !actor.equals(current.subject() + "#" + current.fingerprint()))
                throw new IllegalStateException("Maintenance version changed");
            int changed = authority.update("UPDATE id2_maintainer SET status='REVOKED',version=version+1 "
                    + "WHERE singleton_key=1 AND status='ACTIVE' AND version=?", expectedVersion);
            if (changed != 1) throw new IllegalStateException("Maintenance version changed");
            audit(current.subject(), current.fingerprint(), Role.KEYS.name(), "REVOKE");
        });
    }

    private Maintainer current() {
        return current(false);
    }

    private Maintainer current(boolean locking) {
        List<Maintainer> rows = authority.query("SELECT subject_ref,organization,certificate_sha,allowed_roles,status,version "
                        + "FROM id2_maintainer WHERE singleton_key=1" + (locking ? " FOR UPDATE" : ""),
                (rs, row) -> new Maintainer(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)));
        if (rows.size() > 1) throw new IllegalStateException("Unique maintainer required");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void audit(String subject, String fingerprint, String role, String decision) {
        authority.update("INSERT INTO id2_maintenance_audit(audit_id,subject_ref,certificate_sha,"
                        + "role_code,decision,occurred_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), subject, fingerprint, role, decision);
    }

    private static String fingerprint(HttpServletRequest request) {
        if (!request.isSecure()) return null;
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) return null;
        try {
            chain[0].checkValidity();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
        } catch (Exception invalid) {
            return null;
        }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Maintenance certificate required");
    }
}
