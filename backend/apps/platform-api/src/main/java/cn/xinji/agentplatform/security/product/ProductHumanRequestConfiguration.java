package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit ordinary-human assembly; startup only probes storage, fresh Task creation initializes its scope. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "product.consultation.human-request.enabled", havingValue = "true")
class ProductHumanRequestConfiguration {
    private final String issuer;
    private final ProductRetentionPolicy taskPolicy;
    private final ProductRetentionPolicy goalPolicy;
    private final OrdinaryHumanRetention.Policy humanPolicy;

    ProductHumanRequestConfiguration(Environment environment) {
        for (String prerequisite : new String[]{"product.identity.enabled", "product.source.engine.enabled",
                "product.consultation.comparison.enabled"})
            if (!Boolean.TRUE.equals(environment.getProperty(prerequisite, Boolean.class)))
                throw new IllegalArgumentException("Human requests require " + prerequisite + "=true");
        issuer = required(environment, "product.identity.issuer");
        taskPolicy = policy(environment, "product.consultation.retention");
        goalPolicy = policy(environment, "product.consultation.goal-retention");
        String prefix = "product.consultation.human-request.retention";
        humanPolicy = new OrdinaryHumanRetention.Policy(required(environment, prefix + ".ref"),
                Long.parseLong(required(environment, prefix + ".version")),
                required(environment, prefix + ".evidence-sha256").toLowerCase(java.util.Locale.ROOT));
    }

    @Bean ProductHumanTaskCreation productHumanTaskCreation(JdbcTemplate db, PlatformTransactionManager manager,
            ProductGrantWatermark watermark, ProductConsultationAccess access, JwtDecoder decoder,
            ProductTaskCreationService creation, ObjectMapper mapper) {
        if (!(manager instanceof DataSourceTransactionManager jdbcManager))
            throw new IllegalArgumentException("Human creation requires the bound JDBC transaction manager");
        ProductHumanRequestSchema.require(db, watermark.independentDatabase());
        return new ProductHumanTaskCreation(db, watermark.independentDatabase(), jdbcManager, mapper,
                Clock.systemUTC(), creation, decoder, access, watermark, issuer,
                new ProductHumanCleanupScope.Policy(humanPolicy.reference(), humanPolicy.version(),
                        humanPolicy.evidenceSha256().toLowerCase(java.util.Locale.ROOT)));
    }

    @Bean ProductHumanRequestApplication productHumanRequestApplication(JdbcTemplate db, PlatformTransactionManager manager,
            ProductGrantWatermark watermark, ProductConsultationAccess access, JwtDecoder decoder,
            ProductCollectedPatentTitles originals, ProductComparisonInputApplication inputs,
            ProductComparisonMatrixReader matrices, ProductComparisonParentEvidenceReader parents,
            ProductComparisonJson json, ObjectMapper mapper) {
        if (!(manager instanceof DataSourceTransactionManager jdbcManager))
            throw new IllegalArgumentException("Human requests require the bound JDBC transaction manager");
        Clock clock = Clock.systemUTC();
        JdbcTemplate authority = watermark.independentDatabase();
        ProductHumanRequestSchema.require(db, authority);
        var transactions = new ProductHumanTaskTransaction(db, manager, watermark, access, decoder, issuer, clock);
        var locks = new ProductHumanRequestLocks(db, access, watermark, jdbcManager, issuer, clock);
        var facts = new ProductHumanTaskFacts(db, access, decoder, goalPolicy, mapper, clock);
        var materials = new ProductHumanMaterialReferences(originals);
        var comparisons = new ProductHumanComparisonReferences(db, inputs, matrices, parents, json);
        var cleanup = new ProductHumanCleanupScope(db, authority, jdbcManager, mapper, clock,
                (task, organization, policy, operator, now) -> {
                    throw new IllegalStateException("Request entry cannot initialize human cleanup scope");
                });
        var store = new ProductHumanRequestStore(db, mapper, clock);
        return new ProductHumanRequestApplication(db, authority.getDataSource(), transactions, locks, facts, materials,
                comparisons, cleanup, store, decoder, json, humanPolicy, taskPolicy, clock);
    }

    private static ProductRetentionPolicy policy(Environment environment, String prefix) {
        return new ProductRetentionPolicy(required(environment, prefix + ".ref"),
                Long.parseLong(required(environment, prefix + ".version")), required(environment, prefix + ".evidence-sha256"),
                Duration.parse(required(environment, prefix + ".max-age")));
    }
    private static String required(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Human requests require " + property);
        return value;
    }
}
