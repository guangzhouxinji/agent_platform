package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class ProductPromptSaveConfigurationTest {
    @TempDir Path root;
    AnnotationConfigApplicationContext context;
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void defaultOffRequiresNoIdentityDatabaseOrSaveDependency(){
        context=new AnnotationConfigApplicationContext(ProductPromptSaveConfiguration.class,ProductPromptCreateConfiguration.class);
        assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();
    }
    @Test void createActivationDoesNotActivateSave()throws Exception{
        var x=new ProductPromptSaveHttpFixture(root,false);x.properties.remove("product.consultation.prompt.save-enabled");
        x.properties.put("product.consultation.prompt.create-enabled","true");context=prepare(x,false);context.refresh();
        assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();assertThat(context.getBean(ProductPromptCreateApplication.class)).isNotNull();
    }
    @Test void saveOnlyUsesPrivateBoundedVerifierAndDoesNotWriteIdentity()throws Exception{
        var x=new ProductPromptSaveHttpFixture(root,true);var f=x.f;
        int keys=f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class);
        int events=f.db.queryForObject("SELECT COUNT(*) FROM id2_key_event",Integer.class);
        int grants=f.db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class);
        int mainTimeout=f.db.getQueryTimeout(),authorityTimeout=f.independent.getQueryTimeout();
        context=prepare(x,false);context.getBeanFactory().registerSingleton("sharedDecoder",(JwtDecoder)raw->{throw new AssertionError("SAVE used shared decoder");});
        context.refresh();var app=context.getBean(ProductPromptSaveApplication.class);
        var result=app.request(f.token,f.workspaceId,x.initial.promptId(),"save-existing",x.command);
        assertThat(app.lookup(f.token,f.workspaceId,"save-existing")).isEqualTo(result);
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
        assertThat(context.getBeansOfType(ProductPromptWriteComponents.class)).isEmpty();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class)).isEqualTo(keys);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_key_event",Integer.class)).isEqualTo(events);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isEqualTo(grants);
        assertThat(f.db.getQueryTimeout()).isEqualTo(mainTimeout);assertThat(f.independent.getQueryTimeout()).isEqualTo(authorityTimeout);
    }
    @Test void v008OnlyFailsActualSaveAndRollsBackBothIndependentReservationRows()throws Exception{
        var x=new ProductPromptSaveHttpFixture(root,false);var f=x.f;context=prepare(x,false);context.refresh();
        var app=context.getBean(ProductPromptSaveApplication.class);
        assertThatThrownBy(()->app.request(f.token,f.workspaceId,x.initial.promptId(),"uninstalled-save",x.command))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("CK_IDP_REQUEST");
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request",Integer.class)).isZero();
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("READY");
        assertThat(f.independent.queryForObject("SELECT generation FROM idp_prompt_control",Long.class)).isEqualTo(1);
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_revision_witness",Integer.class)).isEqualTo(1);
        for(String table:new String[]{"conp_revision","conp_event","conp_receipt"})assertThat(f.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"prompt-off","identity-off","missing-master","missing-input","missing-request-table","wrong-manager"})
    void enabledSaveMissingDependenciesFailsStartupWithoutInstallingAnything(String fault)throws Exception{
        var x=new ProductPromptSaveHttpFixture(root,true);var f=x.f;
        switch(fault){
            case "prompt-off"->x.properties.put("product.consultation.prompt.enabled","false");
            case "identity-off"->x.properties.put("product.identity.enabled","false");
            case "missing-master"->x.properties.remove("product.identity.master-key");
            case "missing-input"->x.properties.remove("product.consultation.prompt.input-permissions-ref");
            case "missing-request-table"->f.independent.execute("ALTER TABLE idp_prompt_request RENAME TO unavailable_request");
            default->{ }
        }
        int keys=f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class);
        context=prepare(x,fault.equals("wrong-manager"));assertThatThrownBy(context::refresh).isInstanceOf(RuntimeException.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM id2_signing_key",Integer.class)).isEqualTo(keys);
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("READY");
    }
    private AnnotationConfigApplicationContext prepare(ProductPromptSaveHttpFixture x,boolean wrongManager){
        var c=new AnnotationConfigApplicationContext();c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("save",x.properties));
        c.getBeanFactory().registerSingleton("fixture",x.f);c.getBeanFactory().registerSingleton("manager",wrongManager?x.f.authorityManager:x.f.mainManager);
        c.register(ProductPromptCreateConfigurationTest.Node.class,ProductPromptSaveConfiguration.class,ProductPromptCreateConfiguration.class);return c;
    }
}
