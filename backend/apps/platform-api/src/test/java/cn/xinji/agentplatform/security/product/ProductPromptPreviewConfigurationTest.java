package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class ProductPromptPreviewConfigurationTest {
    @TempDir Path root;
    AnnotationConfigApplicationContext context;
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void defaultOffRequiresNoIdentityStoreOrPermission(){
        context=new AnnotationConfigApplicationContext(ProductPromptPreviewConfiguration.class);
        assertThat(context.getBeansOfType(ProductPromptPreviewApplication.class)).isEmpty();
    }
    @Test void previewOnlyUsesPrivateBoundedVerifierAndV007SchemaWithoutWritesOrOtherLicences()throws Exception{
        var x=new ProductPromptPreviewApplicationFixture(root);var before=x.snapshot();int mainTimeout=x.f.db.getQueryTimeout(),authorityTimeout=x.f.independent.getQueryTimeout();
        context=prepare(x,x.mainManager,x.watermark);context.registerBean("sharedDecoder",JwtDecoder.class,()->raw->{throw new AssertionError("PREVIEW used shared decoder");});context.refresh();
        var application=context.getBean(ProductPromptPreviewApplication.class);var result=application.preview(x.f.token,x.f.workspaceId,x.header.promptId(),x.header.revisionId(),x.command());
        assertThat(result.status()).isEqualTo(200);assertThat(x.snapshot()).isEqualTo(before);
        assertThat(context.getBeansOfType(ProductPromptPreviewPermission.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptWorkspaceAuthorization.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductPromptStore.class)).isEmpty();assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductPromptCopyApplication.class)).isEmpty();
        assertThat(x.properties).doesNotContainKeys("product.consultation.prompt.input-permissions-ref","product.consultation.prompt.copy-input-permissions-ref");
        assertThat(x.f.independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='IDP_PROMPT_REQUEST'",Integer.class)).isZero();
        assertThat(x.f.db.getQueryTimeout()).isEqualTo(mainTimeout);assertThat(x.f.independent.getQueryTimeout()).isEqualTo(authorityTimeout);
        assertThat(x.mainSource.timeouts).isNotEmpty().allMatch(value->value>0&&value<=5);
        assertThat(x.authoritySource.timeouts).isNotEmpty().allMatch(value->value>0&&value<=5);
        assertThat(x.mainSource.statements).noneMatch(sql->sql.startsWith("INSERT ")||sql.startsWith("UPDATE ")||sql.startsWith("DELETE "));
        assertThat(x.authoritySource.statements).noneMatch(sql->sql.startsWith("INSERT ")||sql.startsWith("UPDATE ")||sql.startsWith("DELETE "));
    }
    @ParameterizedTest @ValueSource(strings={"prompt-off","identity-off","master","permission-ref","permission-sha","bindings","audience","wrong-manager","wrong-watermark","missing-main","missing-authority",
            "missing-cleanup-main","missing-cleanup-authority","missing-cleanup-main-column","missing-cleanup-authority-column"})
    void activationFailsClosedWithoutRepairingDependenciesOrInstallingSchema(String fault)throws Exception{
        var x=new ProductPromptPreviewApplicationFixture(root);var manager=x.mainManager;var watermark=x.watermark;
        switch(fault){
            case "prompt-off"->x.properties.put("product.consultation.prompt.enabled","false");
            case "identity-off"->x.properties.put("product.identity.enabled","false");
            case "master"->x.properties.remove("product.identity.master-key");
            case "permission-ref"->x.properties.remove("product.consultation.prompt.preview-test-permissions-ref");
            case "permission-sha"->x.properties.remove("product.consultation.prompt.preview-test-permissions-sha256");
            case "bindings"->x.properties.remove("product.consultation.prompt.bindings-sha256");
            case "audience"->x.properties.remove("product.identity.audience");
            case "wrong-manager"->manager=x.authorityManager;case "wrong-watermark"->watermark=x.f.watermark;
            case "missing-main"->x.f.db.execute("ALTER TABLE conp_event RENAME TO unavailable_event");
            case "missing-authority"->x.f.independent.execute("ALTER TABLE idp_revision_witness RENAME TO unavailable_witness");
            case "missing-cleanup-main"->x.f.db.execute("ALTER TABLE conp_prompt_cleanup_receipt RENAME TO unavailable_cleanup");
            case "missing-cleanup-authority"->x.f.independent.execute("ALTER TABLE idp_prompt_cleanup_request RENAME TO unavailable_cleanup");
            case "missing-cleanup-main-column"->x.f.db.execute("ALTER TABLE conp_prompt_cleanup_receipt RENAME COLUMN manifest_json TO unavailable_manifest");
            case "missing-cleanup-authority-column"->x.f.independent.execute("ALTER TABLE idp_prompt_cleanup_request RENAME COLUMN manifest_json TO unavailable_manifest");
        }
        var before=x.f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class);context=prepare(x,manager,watermark);
        assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        assertThat(x.f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(before);
        assertThat(x.f.independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='IDP_PROMPT_REQUEST'",Integer.class)).isZero();
    }
    @Test void ordinaryReadActivationDoesNotEnablePreviewOrRequireTestPermission()throws Exception{
        var x=new ProductPromptPreviewApplicationFixture(root);x.properties.remove("product.consultation.prompt.preview-enabled");
        x.properties.remove("product.consultation.prompt.preview-test-permissions-ref");x.properties.remove("product.consultation.prompt.preview-test-permissions-sha256");
        context=prepare(x,x.mainManager,x.watermark);context.register(ProductPromptReadConfiguration.class);context.refresh();
        assertThat(context.getBean(ProductPromptReadApplication.class)).isNotNull();assertThat(context.getBeansOfType(ProductPromptPreviewApplication.class)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"main","authority","main-column","authority-column"})
    void ordinaryReadRequiresCompleteCleanupSchema(String fault)throws Exception{
        var x=new ProductPromptPreviewApplicationFixture(root);x.properties.remove("product.consultation.prompt.preview-enabled");
        switch(fault){
            case "main"->x.f.db.execute("ALTER TABLE conp_prompt_cleanup_receipt RENAME TO unavailable_cleanup");
            case "authority"->x.f.independent.execute("ALTER TABLE idp_prompt_cleanup_request RENAME TO unavailable_cleanup");
            case "main-column"->x.f.db.execute("ALTER TABLE conp_prompt_cleanup_receipt RENAME COLUMN manifest_json TO unavailable_manifest");
            case "authority-column"->x.f.independent.execute("ALTER TABLE idp_prompt_cleanup_request RENAME COLUMN manifest_json TO unavailable_manifest");
        }
        var before=x.snapshot();context=prepare(x,x.mainManager,x.watermark);context.register(ProductPromptReadConfiguration.class);
        assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);assertThat(x.snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"same-datasource","same-database-alias"})
    void authorityMustActuallyBeSeparateFromMainDatabase(String condition)throws Exception{
        var x=new ProductPromptPreviewApplicationFixture(root);
        x.f.db.execute("CREATE TABLE id2_authority_watermark(issuer VARCHAR(300),generation BIGINT,state VARCHAR(16),intent_id VARCHAR(100),history_sha CHAR(64))");
        var row=x.f.independent.queryForMap("SELECT * FROM id2_authority_watermark");
        x.f.db.update("INSERT INTO id2_authority_watermark VALUES(?,?,?,?,?)",row.get("ISSUER"),row.get("GENERATION"),row.get("STATE"),row.get("INTENT_ID"),row.get("HISTORY_SHA"));
        var authority=condition.equals("same-datasource")?x.main:new ProductPromptStatementJdbcTemplate(new ProductPromptCreateApplicationTest.TrackingSource(x.f.mainSource));
        var watermark=new ProductGrantWatermark(x.main,authority,ProductPromptWorkspaceAuthorizationFixture.ISSUER);
        var before=x.snapshot();context=prepare(x,x.mainManager,watermark);assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        assertThat(x.snapshot()).isEqualTo(before);
    }
    @Test void zeroVariablesStillRequiresAuthenticatedExactTestPolicy()throws Exception{
        var x=new ProductPromptPreviewApplicationFixture(root,List.of(),"Owned literal only");context=prepare(x,x.mainManager,x.watermark);context.refresh();
        var app=context.getBean(ProductPromptPreviewApplication.class);
        assertThat(app.preview(x.f.token,x.f.workspaceId,x.header.promptId(),x.header.revisionId(),x.command(List.of())).status()).isEqualTo(200);
        java.nio.file.Files.writeString(root.resolve(x.input.permissionRef),"{}");
        assertThatThrownBy(()->app.preview(x.f.token,x.f.workspaceId,x.header.promptId(),x.header.revisionId(),x.command(List.of())))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(ProductPromptAccessDenied.class);
    }
    private AnnotationConfigApplicationContext prepare(ProductPromptPreviewApplicationFixture x,DataSourceTransactionManager manager,ProductGrantWatermark watermark){
        var c=new AnnotationConfigApplicationContext();c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("preview",x.properties));x.register(c,manager,watermark);
        c.register(ProductPromptCreateConfigurationTest.Node.class,ProductPromptPreviewConfiguration.class,ProductPromptCreateConfiguration.class,
                ProductPromptSaveConfiguration.class,ProductPromptCopyConfiguration.class);return c;
    }
}
