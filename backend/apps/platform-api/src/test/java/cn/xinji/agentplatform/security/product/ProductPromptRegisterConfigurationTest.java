package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/** Production assembly over existing real Hikari pools; owned fixtures are not runtime admission. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptRegisterConfigurationTest {
    private static final String PREFIX="product.consultation.prompt.";
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{tls.close();}

    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void disabledRequiresNoIdentityDatabaseOrEvidence(String switchValue) {
        try(var context=new AnnotationConfigApplicationContext()) {
            if(!switchValue.equals("absent"))context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("disabled",Map.of(PREFIX+"register-enabled",switchValue)));
            context.register(ProductPromptRegisterConfiguration.class,ProductPromptRegisterController.class);context.refresh();
            assertThat(context.getBeansOfType(ProductPromptRegisterApplication.class)).isEmpty();
            assertThat(context.getBeansOfType(ProductPromptRegistrationLease.class)).isEmpty();
            assertThat(context.getBeansOfType(ProductPromptRegisterController.class)).isEmpty();
        }
    }

    @Test void startupOnlyProbesSchemaAndPreservesSharedPoolsAndTemplateSettings()throws Exception {
        try(var f=fixture()) {
            var watermark=watermark(f);var properties=properties(f);
            int mainTimeout=f.db.getQueryTimeout(),authorityTimeout=f.independent.getQueryTimeout();
            clear(f);ProductPromptRegistrationLease lease;
            try(var context=context(f,properties,f.db,f.mainManager,watermark)) {
                context.refresh();lease=context.getBean(ProductPromptRegistrationLease.class);
                assertThat(context.getBean(ProductPromptRegisterApplication.class)).isNotNull();
                assertThat(lease.mainSource).isSameAs(f.mainSource);assertThat(lease.authoritySource).isSameAs(f.authoritySource);
                assertThat(lease.mainManager).isSameAs(f.mainManager);assertThat(lease.authorityManager.getDataSource()).isSameAs(f.authoritySource);
                assertThat(f.mainFault.sql).hasSize(3).allMatch(sql->sql.startsWith("SELECT ")&&sql.endsWith(" WHERE 1=0"));
                assertThat(f.authorityFault.sql).hasSize(3).allMatch(sql->sql.startsWith("SELECT ")&&sql.endsWith(" WHERE 1=0"));
                assertThat(f.mainFault.queryTimeouts).hasSize(3).allMatch(seconds->seconds>0&&seconds<=5);
                assertThat(f.authorityFault.queryTimeouts).hasSize(3).allMatch(seconds->seconds>0&&seconds<=5);
                assertThat(f.mainFault.commits).isEmpty();assertThat(f.authorityFault.commits).isEmpty();
                assertThat(f.db.getQueryTimeout()).isEqualTo(mainTimeout);assertThat(f.independent.getQueryTimeout()).isEqualTo(authorityTimeout);
            }
            assertThat(lease.network.isShutdown()).isTrue();assertThat(f.mainSource.isClosed()).isFalse();assertThat(f.authoritySource.isClosed()).isFalse();
            assertEmpty(f);
        }
    }

    @ParameterizedTest @ValueSource(strings={"prompt-off","identity-off","prompt-missing","identity-missing","wrong-manager",
            "wrong-watermark","same-pool","non-hikari-main","non-hikari-authority","unstarted-authority","authority-capacity",
            "suspension","borrow-timeout","validation-timeout"})
    void invalidPoolAdmissionFailsBeforeAnySchemaOrStoreBorrow(String fault)throws Exception {
        try(var f=fixture();var unstarted=new HikariDataSource()) {
            var watermark=watermark(f);var properties=properties(f);JdbcTemplate db=f.db;
            PlatformTransactionManager manager=f.mainManager;HikariDataSource extra=null;
            switch(fault) {
                case "prompt-off"->properties.put(PREFIX+"enabled","false");
                case "identity-off"->properties.put("product.identity.enabled","false");
                case "prompt-missing"->properties.remove(PREFIX+"enabled");
                case "identity-missing"->properties.remove("product.identity.enabled");
                case "wrong-manager"->manager=f.authorityManager;
                case "wrong-watermark"->{db=new JdbcTemplate(f.authoritySource);manager=f.authorityManager;}
                case "same-pool"->watermark.independentDatabase().setDataSource(f.mainSource);
                case "non-hikari-main"->{var wrapped=new DelegatingDataSource(f.mainSource);db=new JdbcTemplate(wrapped);manager=new DataSourceTransactionManager(wrapped);}
                case "non-hikari-authority"->watermark.independentDatabase().setDataSource(new DelegatingDataSource(f.authoritySource));
                case "unstarted-authority"->watermark.independentDatabase().setDataSource(unstarted);
                case "authority-capacity"->f.authoritySource.getHikariConfigMXBean().setMaximumPoolSize(1);
                case "borrow-timeout"->f.authoritySource.getHikariConfigMXBean().setConnectionTimeout(30001);
                case "validation-timeout"->f.authoritySource.getHikariConfigMXBean().setValidationTimeout(501);
                case "suspension"->{var config=new HikariConfig();config.setDataSource(f.authorityFault);
                    config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setAllowPoolSuspension(true);
                    config.setConnectionTimeout(500);config.setValidationTimeout(250);extra=new HikariDataSource(config);
                    watermark.independentDatabase().setDataSource(extra);}
                default->throw new AssertionError(fault);
            }
            clear(f);
            try(var context=context(f,properties,db,manager,watermark)) {
                assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
                assertThat(f.mainFault.sql).isEmpty();assertThat(f.authorityFault.sql).isEmpty();
                assertThat(f.mainFault.foregroundConnectionCalls).doesNotContain("getMetaData","getSchema");
                assertThat(f.authorityFault.foregroundConnectionCalls).doesNotContain("getMetaData","getSchema");
                assertThat(f.mainSource.isClosed()).isFalse();assertThat(f.authoritySource.isClosed()).isFalse();
            }finally{if(extra!=null)extra.close();}
            assertEmpty(f);
        }
    }

    @ParameterizedTest @ValueSource(strings={"evidence-root","bindings-ref","bindings-sha256","register-approval-pins-json",
            "bad-binding","bad-pins","organization-column","workspace-column","receipt-column","maintainer-column","authority-workspace-column","request-column"})
    void missingConfigurationOrSchemaClosesOnlyDedicatedLeaseAndNeverWrites(String fault)throws Exception {
        try(var f=fixture()) {
            var watermark=watermark(f);var properties=properties(f);
            switch(fault) {
                case "bad-binding"->properties.put(PREFIX+"bindings-sha256","b".repeat(64));
                case "bad-pins"->properties.put(PREFIX+"register-approval-pins-json","[]");
                case "organization-column"->f.db.execute("ALTER TABLE con3_organization DROP COLUMN status");
                case "workspace-column"->f.db.execute("ALTER TABLE conp_workspace DROP COLUMN policy_ref");
                case "receipt-column"->f.db.execute("ALTER TABLE conp_workspace_register_receipt DROP COLUMN workspace_sha");
                case "maintainer-column"->f.independent.execute("ALTER TABLE id2_maintainer DROP COLUMN allowed_roles");
                case "authority-workspace-column"->f.independent.execute("ALTER TABLE idp_workspace DROP COLUMN expected_registry_sha");
                case "request-column"->f.independent.execute("ALTER TABLE idp_workspace_register_request DROP COLUMN approval_ref");
                default->properties.remove(PREFIX+fault);
            }
            clear(f);
            try(var context=context(f,properties,f.db,f.mainManager,watermark)) {
                assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
                assertThat(f.mainFault.sql).allMatch(sql->sql.startsWith("SELECT ")&&sql.endsWith(" WHERE 1=0"));
                assertThat(f.authorityFault.sql).allMatch(sql->sql.startsWith("SELECT ")&&sql.endsWith(" WHERE 1=0"));
                assertThat(f.mainSource.isClosed()).isFalse();assertThat(f.authoritySource.isClosed()).isFalse();
            }
            // Direct factory failure also closes its owner even outside Spring's failed-refresh destruction.
            var env=new MockEnvironment();properties.forEach((key,value)->env.setProperty(key,value.toString()));
            var config=new ProductPromptRegisterConfiguration();var lease=config.productPromptRegistrationLease(env,f.db,f.mainManager,watermark);
            assertThatThrownBy(()->config.productPromptRegisterApplication(env,lease,f.mapper)).isInstanceOf(RuntimeException.class);
            assertThat(lease.network.isShutdown()).isTrue();assertEmpty(f);
        }
    }

    private ProductPromptRegisterFixture fixture()throws Exception{return new ProductPromptRegisterFixture(root.resolve(UUID.randomUUID().toString()),tls);}
    private ProductGrantWatermark watermark(ProductPromptRegisterFixture f) {
        String issuer="owned-register-configuration";
        f.independent.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",issuer,ProductComparisonJson.hash(""));
        return new ProductGrantWatermark(f.db,new JdbcTemplate(f.authoritySource),issuer);
    }
    private Map<String,Object> properties(ProductPromptRegisterFixture f)throws Exception {
        var properties=new HashMap<String,Object>();properties.put(PREFIX+"register-enabled","true");
        properties.put(PREFIX+"enabled","true");properties.put("product.identity.enabled","true");
        properties.put(PREFIX+"evidence-root",f.root.toString());properties.put(PREFIX+"bindings-ref",f.bindingsRef);
        properties.put(PREFIX+"bindings-sha256",ProductCapturedArchive.digest(Files.readAllBytes(f.root.resolve(f.bindingsRef))));
        properties.put(PREFIX+"register-approval-pins-json",f.mapper.writeValueAsString(List.of(Map.of(
                "workspaceId",f.workspaceId,"approvalRef",f.registerRef,"approvalSha",ProductCapturedArchive.digest(Files.readAllBytes(f.root.resolve(f.registerRef)))))));
        return properties;
    }
    private AnnotationConfigApplicationContext context(ProductPromptRegisterFixture f,Map<String,Object> properties,
            JdbcTemplate db,PlatformTransactionManager manager,ProductGrantWatermark watermark) {
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned",properties));
        context.getBeanFactory().registerSingleton("db",db);context.getBeanFactory().registerSingleton("manager",manager);
        context.getBeanFactory().registerSingleton("watermark",watermark);context.getBeanFactory().registerSingleton("mapper",f.mapper);
        context.register(ProductPromptRegisterConfiguration.class);return context;
    }
    private static void clear(ProductPromptRegisterFixture f){f.mainFault.clear();f.authorityFault.clear();}
    private static void assertEmpty(ProductPromptRegisterFixture f) {
        for(String table:List.of("conp_workspace","conp_workspace_register_receipt"))assertThat(f.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        for(String table:List.of("idp_workspace","idp_workspace_register_request"))assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
}
