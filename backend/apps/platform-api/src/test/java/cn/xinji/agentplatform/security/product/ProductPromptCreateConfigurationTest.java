package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action.CREATE;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class ProductPromptCreateConfigurationTest {
    @TempDir Path root;
    AnnotationConfigApplicationContext context;
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void absentFeatureRequiresNoDatabaseIdentityOrPermissionConfiguration(){
        context=new AnnotationConfigApplicationContext(ProductPromptCreateConfiguration.class);
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();
    }
    @Test void explicitOffKeepsReadAvailableWithoutInputDirectoryOrRequestMigration()throws Exception{
        var f=new ProductPromptWorkspaceAuthorizationFixture(root);var properties=readProperties(f);properties.put("product.consultation.prompt.create-enabled","false");
        context=prepare(f,properties,true);context.refresh();
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();
        assertThat(context.getBean(ProductPromptReadApplication.class)).isNotNull();
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='IDP_PROMPT_REQUEST'",Long.class)).isZero();
    }
    @Test void productionAssemblyUsesExistingKeyAndOwnDecoderWithoutChangingSharedReadObjects()throws Exception{
        var f=new ProductPromptWorkspaceAuthorizationFixture(root);f.grant(CREATE);
        migrate(f.authoritySource,"identity-authority/V008__product_prompt_create_request.sql");
        var input=new ProductPromptInputPermissionFixture(f);var properties=properties(f,input);
        int keys=f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class);
        int events=f.db.queryForObject("SELECT COUNT(*) FROM id2_key_event",Integer.class);
        int grants=f.db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class);
        int originalMainTimeout=f.db.getQueryTimeout(),originalAuthorityTimeout=f.independent.getQueryTimeout();
        context=prepare(f,properties,true);
        // The CREATE application must not route its inner verification through this unbounded shared decoder.
        context.getBeanFactory().registerSingleton("sharedDecoder",(JwtDecoder)raw->{throw new AssertionError("Shared decoder used by CREATE");});
        context.refresh();
        var app=context.getBean(ProductPromptCreateApplication.class);
        var first=app.request(f.token,f.workspaceId,"create-original-key",input.command);
        assertThat(app.lookup(f.token,f.workspaceId,"create-original-key")).isEqualTo(first);
        assertThat(app.request(f.token,f.workspaceId,"create-original-key",input.command)).isEqualTo(first);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("READY");
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class)).isEqualTo(keys);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_key_event",Integer.class)).isEqualTo(events);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isEqualTo(grants);
        assertThat(f.db.getQueryTimeout()).isEqualTo(originalMainTimeout);assertThat(f.independent.getQueryTimeout()).isEqualTo(originalAuthorityTimeout);
        assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
        assertThat(context.getBean(ProductPromptReadApplication.class)).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"prompt-off","identity-off","missing-master","missing-audience","invalid-window","missing-directory-pin","bad-directory-pin","missing-request-table","missing-request-column","wrong-manager",
            "missing-cleanup-main","missing-cleanup-authority","missing-cleanup-main-column","missing-cleanup-authority-column"})
    void enabledCreateFailsStartupOnMissingAdmissionWithoutRegistrationOrKeyWrites(String fault)throws Exception{
        var f=new ProductPromptWorkspaceAuthorizationFixture(root);var input=new ProductPromptInputPermissionFixture(f);
        if(!fault.equals("missing-request-table"))migrate(f.authoritySource,"identity-authority/V008__product_prompt_create_request.sql");
        var properties=properties(f,input);
        switch(fault){
            case "prompt-off"->properties.put("product.consultation.prompt.enabled","false");
            case "identity-off"->properties.put("product.identity.enabled","false");
            case "missing-master"->properties.remove("product.identity.master-key");
            case "missing-audience"->properties.remove("product.identity.audience");
            case "invalid-window"->properties.put("product.identity.token-verification-window","PT0S");
            case "missing-directory-pin"->properties.remove("product.consultation.prompt.input-permissions-sha256");
            case "bad-directory-pin"->properties.put("product.consultation.prompt.input-permissions-sha256","b".repeat(64));
            case "missing-request-column"->f.independent.execute("ALTER TABLE idp_prompt_request DROP COLUMN input_permission_ref");
            case "missing-cleanup-main"->f.db.execute("ALTER TABLE conp_prompt_cleanup_receipt RENAME TO unavailable_cleanup");
            case "missing-cleanup-authority"->f.independent.execute("ALTER TABLE idp_prompt_cleanup_request RENAME TO unavailable_cleanup");
            case "missing-cleanup-main-column"->f.db.execute("ALTER TABLE conp_prompt_cleanup_receipt RENAME COLUMN manifest_json TO unavailable_manifest");
            case "missing-cleanup-authority-column"->f.independent.execute("ALTER TABLE idp_prompt_cleanup_request RENAME COLUMN manifest_json TO unavailable_manifest");
            default->{ }
        }
        int keys=f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class);
        context=prepare(f,properties,false);
        context.getBeanFactory().registerSingleton("manager",fault.equals("wrong-manager")?f.authorityManager:f.mainManager);
        assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isZero();
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_control",Integer.class)).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class)).isEqualTo(keys);
    }
    private AnnotationConfigApplicationContext prepare(ProductPromptWorkspaceAuthorizationFixture f,Map<String,Object> properties,boolean read){
        var value=new AnnotationConfigApplicationContext();value.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",properties));
        value.getBeanFactory().registerSingleton("fixture",f);value.register(Node.class,ProductPromptCreateConfiguration.class);
        if(read)value.register(ProductPromptReadConfiguration.class);
        if(read)value.getBeanFactory().registerSingleton("manager",f.mainManager);
        return value;
    }
    private Map<String,Object> readProperties(ProductPromptWorkspaceAuthorizationFixture f)throws Exception{
        String ref="configuration-workspaces.json",sha=f.write(ref,Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        var properties=new HashMap<String,Object>();properties.put("product.consultation.prompt.enabled","true");properties.put("product.identity.enabled","true");
        properties.put("product.identity.issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER);
        properties.put("product.consultation.prompt.evidence-root",root.toString());properties.put("product.consultation.prompt.bindings-ref",ref);
        properties.put("product.consultation.prompt.bindings-sha256",sha);return properties;
    }
    private Map<String,Object> properties(ProductPromptWorkspaceAuthorizationFixture f,ProductPromptInputPermissionFixture input)throws Exception{
        var properties=readProperties(f);properties.put("product.consultation.prompt.create-enabled","true");
        properties.put("product.consultation.prompt.input-permissions-ref",input.directoryRef);properties.put("product.consultation.prompt.input-permissions-sha256",input.directorySha);
        properties.put("product.identity.audience",ProductPromptWorkspaceAuthorizationFixture.AUDIENCE);
        properties.put("product.identity.master-key",f.masterKeyBase64);properties.put("product.identity.token-verification-window","PT5M");return properties;
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class Node {
        @Bean JdbcTemplate db(ProductPromptWorkspaceAuthorizationFixture f){return f.db;}
        @Bean ProductGrantWatermark watermark(ProductPromptWorkspaceAuthorizationFixture f){return f.watermark;}
        @Bean ObjectMapper mapper(ProductPromptWorkspaceAuthorizationFixture f){return f.mapper;}
        @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(JwtDecoder.class)
        JwtDecoder decoder(ProductPromptWorkspaceAuthorizationFixture f){return f.decoder;}
    }
}
