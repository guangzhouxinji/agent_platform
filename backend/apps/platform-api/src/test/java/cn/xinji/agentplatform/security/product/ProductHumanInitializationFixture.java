package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Real signed identity, normal creation and two SQL stores; only the maintenance lease is controlled. */
final class ProductHumanInitializationFixture {
    final ProductTaskCreationFixture base = new ProductTaskCreationFixture();
    Instant now = base.now;
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    };
    final ProductHumanCleanupScope.Policy policy = new ProductHumanCleanupScope.Policy("human-v3", 3, "e".repeat(64));
    final ProductHumanInitialization.Binding binding = new ProductHumanInitialization.Binding(
            UUID.randomUUID().toString(), "controlled-lease", "controlled-target", "primary", "independent",
            "controlled-maintainer", ProductHumanInitialization.ACTION, "c".repeat(64), base.now.plusSeconds(900));
    BooleanSupplier denied = () -> false;
    BooleanSupplier crossDeadline = () -> false;
    final ProductHumanInitialization.CurrentLease lease = new ProductHumanInitialization.CurrentLease() {
        public ProductHumanInitialization.Binding binding() { return binding; }
        public void requireCurrent(ProductHumanInitialization.Binding expected, Instant at) {
            if (!binding.equals(expected)) throw new IllegalStateException("Wrong controlled lease binding");
            if (denied.getAsBoolean()) throw new IllegalStateException("Controlled lease lost");
            if (crossDeadline.getAsBoolean()) now = binding.expiresAt();
        }
    };
    final JwtDecoder decoder;
    final String token;

    ProductHumanInitializationFixture() throws Exception {
        migrate(base.db.getDataSource(), "dm8/V109__product_human_request.sql", "dm8/V110__product_human_cleanup_scope.sql");
        migrate(base.authority.getDataSource(), "identity-authority/V006__consultation_human_cleanup_scope.sql");
        base.db.update("UPDATE id2_signing_key SET status='REVOKED' WHERE kid='key'");
        var keys = new ProductSigningKeys(base.db, base.transactions, ProductTaskCreationFixture.ISSUER,
                Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(5), clock);
        String kid = keys.rotate("controlled-key-maintainer");
        base.db.update("UPDATE id2_session SET kid=?", kid);
        token = new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer(ProductTaskCreationFixture.ISSUER).subject("member").audience(List.of("human-api"))
                .issuedAt(now).notBefore(now.minusSeconds(1)).expiresAt(now.plusSeconds(3600))
                .id(base.jwt.getId()).claim("sid", base.jwt.getClaimAsString("sid")).build())).getTokenValue();
        decoder = new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys, base.db, base.watermark,
                ProductTaskCreationFixture.ISSUER, "human-api");
        base.grant();
    }

    ProductHumanInitialization kernel() { return kernel(base.using(base.directory, clock)); }
    ProductHumanInitialization kernel(ProductTaskCreationService service) {
        return new ProductHumanInitialization(base.db, base.authority,
                new DataSourceTransactionManager(base.db.getDataSource()), new ObjectMapper().findAndRegisterModules(),
                clock, service, decoder, lease);
    }
    ProductHumanInitialization.Result execute(ProductHumanInitialization kernel, String request) {
        return kernel.execute(token, request, base.input, policy, base.expires, base.now, base.expires);
    }
    int count(JdbcTemplate db, String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int committed(String table) {
        try (var connection = base.db.getDataSource().getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getInt(1);
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }
    String committedAuthorityState() {
        try (var connection = base.authority.getDataSource().getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT state FROM id6_human_cleanup_scope")) {
            return rows.next() ? rows.getString(1) : null;
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }
}
