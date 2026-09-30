package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** One independently registered, auditable maintenance principal; no header supplies identity. */
public final class ProductMaintenanceProof {
    public enum Role { SUBJECTS, GRANTS, KEYS, SOURCES }
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
        List<Maintainer> rows = authority.query("SELECT subject_ref,organization,certificate_sha,allowed_roles,status,version "
                        + "FROM id2_maintainer WHERE singleton_key=1",
                (rs, row) -> new Maintainer(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)));
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
