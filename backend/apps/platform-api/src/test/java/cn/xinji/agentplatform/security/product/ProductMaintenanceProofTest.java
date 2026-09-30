package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

class ProductMaintenanceProofTest {
    @Test void oneNamedCertificateIsAuditedAndRevokedAcrossInstances() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:maintainer-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        JdbcTemplate db = new JdbcTemplate(source);
        byte[] certificate = "isolated-certificate-bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate));
        db.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,certificate_sha,"
                + "allowed_roles,status,version) VALUES(1,?,?,?,?,'ACTIVE',1)", "named-maintainer",
                "organization", fingerprint, "GRANTS,KEYS");
        var first = new ProductMaintenanceProof(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        var second = new ProductMaintenanceProof(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        X509Certificate peer = mock(X509Certificate.class);
        when(peer.getEncoded()).thenReturn(certificate);
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {peer});
        assertThat(first.require(request, ProductMaintenanceProof.Role.GRANTS))
                .isEqualTo("named-maintainer#" + fingerprint);
        assertThatThrownBy(() -> second.require(request, ProductMaintenanceProof.Role.SUBJECTS))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        first.revoke(request, 1);
        assertThatThrownBy(() -> second.require(request, ProductMaintenanceProof.Role.GRANTS))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT version FROM id2_maintainer WHERE singleton_key=1", Long.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit WHERE decision='ALLOW'",
                Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit WHERE decision='DENY'",
                Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit WHERE decision='REVOKE'",
                Integer.class)).isEqualTo(1);
    }
}
