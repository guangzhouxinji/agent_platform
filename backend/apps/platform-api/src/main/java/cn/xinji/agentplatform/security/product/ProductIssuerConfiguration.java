package cn.xinji.agentplatform.security.product;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public class ProductIssuerConfiguration {
    public ProductIssuerConfiguration(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("test-synthetic", "public-local-test", "dm8-integration")))
            throw new IllegalStateException("Product issuer cannot share a development or probe profile");
    }

    @Bean
    ProductSigningKeys productSigningKeys(JdbcTemplate db, PlatformTransactionManager manager,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.identity.master-key:}") String master,
            @Value("${product.identity.token-verification-window:}") String window) {
        if (window.isBlank()) throw new IllegalArgumentException("Token verification window is required");
        return new ProductSigningKeys(db, new TransactionTemplate(manager), issuer, master,
                Duration.parse(window), Clock.systemUTC());
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${product.identity.issuer:}") String issuer) {
        if (issuer.isBlank() || !issuer.startsWith("https://") || issuer.endsWith("/"))
            throw new IllegalArgumentException("A fixed HTTPS issuer is required");
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean RegisteredClientRepository registeredClientRepository(JdbcTemplate db) {
        return new ProductRegisteredClients(new JdbcRegisteredClientRepository(db), db);
    }

    @Bean OAuth2AuthorizationService authorizationService(JdbcTemplate db, RegisteredClientRepository clients,
            PlatformTransactionManager manager, @Value("${product.identity.issuer:}") String issuer) {
        return new ProductAuthorizationService(new JdbcOAuth2AuthorizationService(db, clients), db,
                new TransactionTemplate(manager), issuer);
    }

    @Bean OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate db,
                                                                        RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(db, clients);
    }

    @Bean JwtDecoder jwtDecoder(JWKSource<SecurityContext> keys, JdbcTemplate db,
            ProductGrantWatermark watermark,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.identity.audience:}") String audience) {
        if (audience.isBlank()) throw new IllegalArgumentException("Fixed audience is required");
        JwtDecoder verifier = OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
        return raw -> {
            Jwt jwt = verifier.decode(raw);
            String sid = jwt.getClaimAsString("sid");
            String jti = jwt.getId();
            String kid = jwt.getHeaders().get("kid") instanceof String value ? value : null;
            if (jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                    || !jwt.getAudience().contains(audience)
                    || sid == null || jti == null || kid == null || jwt.getExpiresAt() == null)
                throw new BadJwtException("Invalid product token claims");
            Integer active = db.queryForObject("SELECT COUNT(*) FROM id2_session s "
                            + "JOIN id2_subject u ON u.issuer=s.issuer AND u.subject_id=s.subject_id "
                            + "JOIN id2_signing_key k ON k.kid=s.kid AND k.issuer=s.issuer "
                            + "WHERE s.issuer=? AND s.sid=? AND s.jti=? AND s.subject_id=? "
                            + "AND s.kid=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP "
                            + "AND u.status='ACTIVE' AND (k.status='ACTIVE' OR "
                            + "(k.status='VERIFY_ONLY' AND k.verify_until>CURRENT_TIMESTAMP))",
                    Integer.class, issuer, sid, jti, jwt.getSubject(), kid);
            if (active == null || active != 1) throw new BadJwtException("Product session is no longer active");
            watermark.assertCurrent();
            return jwt;
        };
    }

    @Bean OAuth2TokenCustomizer<JwtEncodingContext> productTokenClaims(
            @Value("${product.identity.audience:}") String audience) {
        if (audience.isBlank()) throw new IllegalArgumentException("Fixed audience is required");
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(java.util.List.of(audience));
                context.getClaims().claim("sid", java.util.UUID.randomUUID().toString());
            }
        };
    }

    @Bean PasswordEncoder productPasswordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean ProductGrants productGrants(JdbcTemplate db, PlatformTransactionManager manager) {
        return new ProductGrants(db, new TransactionTemplate(manager), Clock.systemUTC());
    }

    @Bean ProductSubjects productSubjects(JdbcTemplate db, PlatformTransactionManager manager,
            PasswordEncoder passwords, @Value("${product.identity.issuer:}") String issuer,
            @Value("${product.identity.activation-lifetime:}") String activationLifetime,
            @Value("${product.identity.minimum-password-length:0}") int minimumPasswordLength) {
        if (activationLifetime.isBlank()) throw new IllegalArgumentException("Activation lifetime is required");
        return new ProductSubjects(db, new TransactionTemplate(manager), passwords, Clock.systemUTC(),
                issuer, Duration.parse(activationLifetime), minimumPasswordLength);
    }

    @Bean UserDetailsService productUsers(JdbcTemplate db,
                                         @Value("${product.identity.issuer:}") String issuer) {
        return name -> db.query("SELECT subject_id,password_hash,status FROM id2_subject "
                        + "WHERE issuer=? AND login_name=? AND password_hash IS NOT NULL", (rs, row) -> {
                    if (!"ACTIVE".equals(rs.getString(3))) throw new UsernameNotFoundException("Inactive subject");
                    return User.withUsername(rs.getString(1)).password(rs.getString(2)).roles("SUBJECT").build();
                }, issuer, name).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Unknown subject"));
    }

    @Bean
    @Order(1)
    SecurityFilterChain issuerEndpoints(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).oidc(Customizer.withDefaults());
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(
                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain issuerLogin(HttpSecurity http) throws Exception {
        return http.securityMatcher("/login")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .build();
    }

}
