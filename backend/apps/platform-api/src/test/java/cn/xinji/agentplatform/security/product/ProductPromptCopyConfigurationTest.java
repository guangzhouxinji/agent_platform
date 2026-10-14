package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class ProductPromptCopyConfigurationTest {
    @TempDir Path root;
    AnnotationConfigApplicationContext context;
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void defaultOffHasNoIdentityStoreOrOtherOperationDependency(){
        context=new AnnotationConfigApplicationContext(ProductPromptCopyConfiguration.class,ProductPromptCreateConfiguration.class,ProductPromptSaveConfiguration.class);
        assertThat(context.getBeansOfType(ProductPromptCopyApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();
    }
    @Test void copyOnlyUsesDedicatedPermissionPrivateVerifierAndNoIdentityWrites()throws Exception{
        var x=new ProductPromptCopyHttpFixture(root);var f=x.f;
        int keys=f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class);
        int events=f.db.queryForObject("SELECT COUNT(*) FROM id2_key_event",Integer.class);
        int grants=f.db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class);
        int mainTimeout=f.db.getQueryTimeout(),authorityTimeout=f.independent.getQueryTimeout();
        context=prepare(x);context.registerBean("sharedDecoder",JwtDecoder.class,
                ()->raw->{throw new AssertionError("COPY used shared decoder");});context.refresh();
        var app=context.getBean(ProductPromptCopyApplication.class);
        var receipt=app.request(f.token,f.workspaceId,x.source.promptId(),"copy-only",x.command);
        assertThat(app.lookup(f.token,f.workspaceId,x.target,"copy-only")).isEqualTo(receipt);
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductPromptWriteComponents.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptInputPermission.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductPromptCopyInputPermission.class)).isEmpty();assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
        assertThat(x.properties).doesNotContainKeys("product.consultation.prompt.input-permissions-ref","product.consultation.prompt.input-permissions-sha256");
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class)).isEqualTo(keys);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_key_event",Integer.class)).isEqualTo(events);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isEqualTo(grants);
        assertThat(f.db.getQueryTimeout()).isEqualTo(mainTimeout);assertThat(f.independent.getQueryTimeout()).isEqualTo(authorityTimeout);
        assertThat(x.tracked.mainSource.timeouts).allMatch(value->value>0&&value<=30);
        assertThat(x.tracked.authoritySource.timeouts).allMatch(value->value>0&&value<=30);
    }
    @ParameterizedTest @ValueSource(strings={"prompt-off","identity-off","missing-master","missing-copy-ref","missing-copy-sha","legacy-fallback","wrong-manager","same-database"})
    void enabledCopyFailsClosedForMissingDependenciesAndNeverUsesLegacyFallback(String fault)throws Exception{
        var x=new ProductPromptCopyHttpFixture(root);var f=x.f;
        switch(fault){
            case "prompt-off"->x.properties.put("product.consultation.prompt.enabled","false");
            case "identity-off"->x.properties.put("product.identity.enabled","false");
            case "missing-master"->x.properties.remove("product.identity.master-key");
            case "missing-copy-ref"->x.properties.remove("product.consultation.prompt.copy-input-permissions-ref");
            case "missing-copy-sha"->x.properties.remove("product.consultation.prompt.copy-input-permissions-sha256");
            case "legacy-fallback"->{x.legacyInput();x.properties.remove("product.consultation.prompt.copy-input-permissions-ref");}
            default->{ }
        }
        int keys=f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class);
        context=prepare(x);
        if(fault.equals("wrong-manager"))context.addBeanFactoryPostProcessor(factory->{((org.springframework.beans.factory.support.DefaultListableBeanFactory)factory).destroySingleton("manager");factory.registerSingleton("manager",x.tracked.authorityManager);});
        if(fault.equals("same-database")){
            f.db.execute("CREATE TABLE id2_authority_watermark(issuer VARCHAR(300),generation BIGINT,state VARCHAR(16),intent_id VARCHAR(100),history_sha CHAR(64))");
            var state=f.independent.queryForMap("SELECT * FROM id2_authority_watermark");
            f.db.update("INSERT INTO id2_authority_watermark VALUES(?,?,?,?,?)",state.get("ISSUER"),state.get("GENERATION"),state.get("STATE"),state.get("INTENT_ID"),state.get("HISTORY_SHA"));
            context.addBeanFactoryPostProcessor(factory->{((org.springframework.beans.factory.support.DefaultListableBeanFactory)factory).destroySingleton("watermark");factory.registerSingleton("watermark",
                new ProductGrantWatermark(x.tracked.main,x.tracked.main,ProductPromptWorkspaceAuthorizationFixture.ISSUER));});
        }
        assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request WHERE operation='COPY'",Integer.class)).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class)).isEqualTo(keys);
    }
    @ParameterizedTest @ValueSource(strings={"source_workspace_id","source_prompt_id","source_revision_id","source_revision_number","source_record_sha","source_body_until","source_audit_until"})
    void copyProbesEverySourceColumnWithoutClaimingConstraintInstallation(String column)throws Exception{
        var x=new ProductPromptCopyHttpFixture(root);x.f.independent.execute("ALTER TABLE idp_prompt_request RENAME COLUMN "+column+" TO unavailable_"+column);
        context=prepare(x);assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        assertThat(x.f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
    }
    @Test void legacyCreateAndSaveAssembleAgainstV009WithoutCopyColumns()throws Exception{
        var x=new ProductPromptSaveHttpFixture(root,true);x.f.grant(ProductPromptWorkspaceEvidence.Action.CREATE);
        x.properties.put("product.consultation.prompt.create-enabled","true");context=legacy(x.f,x.properties);context.refresh();
        assertThat(context.getBean(ProductPromptCreateApplication.class)).isNotNull();assertThat(context.getBeansOfType(ProductPromptCopyApplication.class)).isEmpty();
        var saved=context.getBean(ProductPromptSaveApplication.class).request(x.f.token,x.f.workspaceId,x.initial.promptId(),"legacy-save",x.command);
        assertThat(saved.generation()).isEqualTo(2);
        assertThat(x.f.independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='IDP_PROMPT_REQUEST' AND COLUMN_NAME='SOURCE_RECORD_SHA'",Integer.class)).isZero();
    }
    @Test void legacyCreateStillWritesAgainstV008WithCopyExplicitlyOff()throws Exception{
        var x=new ProductPromptCopyHttpFixture(root);
        // A separate old-schema fixture prevents V010 from becoming an accidental CREATE prerequisite.
        Path oldRoot=java.nio.file.Files.createDirectories(root.resolve("old"));var old=new ProductPromptCreateApplicationTest.Fixture(oldRoot);
        var properties=new java.util.HashMap<String,Object>(x.properties);properties.put("product.consultation.prompt.copy-enabled","false");
        properties.put("product.consultation.prompt.create-enabled","true");properties.put("product.consultation.prompt.evidence-root",old.base.root.toString());
        properties.put("product.consultation.prompt.bindings-ref","create-workspace-bindings.json");
        properties.put("product.consultation.prompt.bindings-sha256",ProductComparisonJson.hash(java.nio.file.Files.readString(old.base.root.resolve("create-workspace-bindings.json"))));
        properties.put("product.consultation.prompt.input-permissions-ref",old.input.directoryRef);properties.put("product.consultation.prompt.input-permissions-sha256",old.input.directorySha);
        properties.put("product.identity.master-key",old.base.masterKeyBase64);context=legacy(old.base,properties);context.refresh();
        var result=context.getBean(ProductPromptCreateApplication.class).request(old.base.token,old.base.workspaceId,"legacy-create",old.input.command);
        assertThat(result.generation()).isEqualTo(1);assertThat(context.getBeansOfType(ProductPromptCopyApplication.class)).isEmpty();
        assertThat(old.base.independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='IDP_PROMPT_REQUEST' AND COLUMN_NAME='SOURCE_RECORD_SHA'",Integer.class)).isZero();
    }
    private AnnotationConfigApplicationContext prepare(ProductPromptCopyHttpFixture x){
        var c=new AnnotationConfigApplicationContext();c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("copy",x.properties));x.register(c);
        c.register(ProductPromptCreateConfigurationTest.Node.class,ProductPromptCreateConfiguration.class,ProductPromptSaveConfiguration.class,ProductPromptCopyConfiguration.class);return c;
    }
    private AnnotationConfigApplicationContext legacy(ProductPromptWorkspaceAuthorizationFixture f,Map<String,Object> properties){
        var c=new AnnotationConfigApplicationContext();c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy",properties));
        c.addBeanFactoryPostProcessor(factory->{factory.registerSingleton("fixture",f);factory.registerSingleton("manager",f.mainManager);});
        c.register(ProductPromptCreateConfigurationTest.Node.class,ProductPromptCreateConfiguration.class,ProductPromptSaveConfiguration.class,ProductPromptCopyConfiguration.class);return c;
    }
}
