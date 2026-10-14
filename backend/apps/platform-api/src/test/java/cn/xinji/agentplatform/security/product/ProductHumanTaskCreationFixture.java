package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Real signed identity, original creation/authorization and two SQL stores; no maintenance admission. */
final class ProductHumanTaskCreationFixture {
    final ProductHumanInitializationFixture original = new ProductHumanInitializationFixture();
    final ProductTaskCreationFixture base = original.base;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final DataSourceTransactionManager manager = new DataSourceTransactionManager(base.db.getDataSource());
    final Jwt jwt;
    Runnable beforeDecode = () -> {};
    final AtomicBoolean changed = new AtomicBoolean();
    final JwtDecoder decoder = token -> { beforeDecode.run(); return original.decoder.decode(token); };

    ProductHumanTaskCreationFixture() throws Exception {
        migrate(base.db.getDataSource(), "dm8/V033__product_oauth2.sql", "dm8/V046__product_workload_subject.sql",
                "dm8/V057__product_oauth_client_control.sql", "dm8/V111__product_human_body_access_deadline.sql");
        base.db.update("INSERT INTO oauth2_registered_client(id,client_id,client_name,client_authentication_methods,"
                + "authorization_grant_types,scopes,client_settings,token_settings) VALUES('client','browser','Browser',"
                + "'none','authorization_code','openid','{}','{}')");
        base.db.update("INSERT INTO id2_client_current VALUES('browser','client','https://example.org/callback','ACTIVE',1,?)",
                Timestamp.from(base.now));
        jwt = original.decoder.decode(original.token);
    }

    ProductHumanTaskCreation service() { return service(base.using(base.directory, original.clock)); }
    ProductHumanTaskCreation service(ProductTaskCreationService creation) {
        return new ProductHumanTaskCreation(base.db, base.authority, manager, mapper, original.clock,
                creation, decoder, base.access, base.watermark, ProductTaskCreationFixture.ISSUER, original.policy);
    }
    int count(String table) { return original.count(table.startsWith("id6_") ? base.authority : base.db, table); }
    String authorityState() { return base.authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class); }
}
