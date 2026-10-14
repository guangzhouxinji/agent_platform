package cn.xinji.agentplatform.enforcement.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;

/** Startup wiring component test; in-memory test client is not product identity evidence. */
class ControlledReadDeploymentTest {
    @TempDir Path root;
    private final ObjectMapper mapper=new ObjectMapper();
    ApplicationContextRunner runner(boolean pkce,boolean consent)throws Exception {
        return runner(pkce,consent,true);
    }
    ApplicationContextRunner runner(boolean pkce,boolean consent,boolean openid)throws Exception {
        var input=new LinkedHashMap<>(new ControlledReadPolicyTest().input());input.put("expiresAt",Instant.now().plusSeconds(3600).toString());
        Path file=root.resolve("policy.json");Files.writeString(file,mapper.writeValueAsString(input));
        var builder=RegisteredClient.withId("TEST-REGISTERED-ID").clientId("TEST-CLIENT")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://127.0.0.1:5174/app/enforcement/document-drafts")
                .clientSettings(ClientSettings.builder().requireProofKey(pkce).requireAuthorizationConsent(consent).build());
        if(openid)builder.scope("openid");
        var client=builder.build();
        RegisteredClientRepository clients=new RegisteredClientRepository(){
            public void save(RegisteredClient ignored){throw new AssertionError("Startup must not save client");}
            public RegisteredClient findById(String id){return client;}
            public RegisteredClient findByClientId(String id){return "TEST-CLIENT".equals(id)?client:null;}
        };
        return new ApplicationContextRunner().withUserConfiguration(EnforcementControlledReadDeployment.class)
                .withBean(ObjectMapper.class,()->mapper).withBean(RegisteredClientRepository.class,()->clients)
                .withPropertyValues("enforcement.deployment.controlled-read.enabled=true",
                    "product.identity.enabled=true","enforcement.v2.enabled=true",
                    "product.identity.audience=https://127.0.0.1:5444/v2",
                    "enforcement.deployment.controlled-read.policy-file="+file,
                    "enforcement.deployment.controlled-read.policy-sha256="+ControlledReadPolicyTest.sha(Files.readAllBytes(file)),
                    "enforcement.v2.job-pack.public-candidates-path=","enforcement.v2.cleanup.enabled=false",
                    "server.address=127.0.0.1","server.ssl.enabled=true","server.forward-headers-strategy=none",
                    "spring.sql.init.mode=never","spring.flyway.enabled=false","spring.liquibase.enabled=false",
                    "spring.mvc.hiddenmethod.filter.enabled=false","product.identity.issuer=https://127.0.0.1:5444");
    }
    @Test void explicitSafeConfigurationRegistersFence()throws Exception {
        runner(true,false).run(ctx->{assertThat(ctx).hasNotFailed();assertThat(ctx).hasSingleBean(ControlledReadPolicy.class);
            var registration=ctx.getBean("controlledReadIngress",org.springframework.boot.web.servlet.FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE);assertThat(registration.getUrlPatterns()).containsExactly("/*");});
    }
    @Test void missingPolicyFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(EnforcementControlledReadDeployment.class)
            .withBean(ObjectMapper.class,()->mapper).withPropertyValues("enforcement.deployment.controlled-read.enabled=true")
            .run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void nonemptyImporterOrCleanupEnabledFailsStartup()throws Exception {
        runner(true,false).withPropertyValues("enforcement.v2.job-pack.public-candidates-path=candidates.json").run(ctx->assertThat(ctx).hasFailed());
        runner(true,false).withPropertyValues("enforcement.v2.cleanup.enabled=true").run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void unsafeNetworkOrInitializationFailsStartup()throws Exception {
        runner(true,false).withPropertyValues("server.address=0.0.0.0").run(ctx->assertThat(ctx).hasFailed());
        runner(true,false).withPropertyValues("spring.sql.init.mode=always").run(ctx->assertThat(ctx).hasFailed());
        runner(true,false).withPropertyValues("server.forward-headers-strategy=framework").run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void pkceAndExistingConsentMustMatch()throws Exception {
        runner(false,false).run(ctx->assertThat(ctx).hasFailed());runner(true,true).run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void disabledDeploymentDoesNotRegisterAnyFence()throws Exception {
        runner(true,false).withPropertyValues("enforcement.deployment.controlled-read.enabled=false")
            .run(ctx->{assertThat(ctx).hasNotFailed();assertThat(ctx).doesNotHaveBean(ControlledReadPolicy.class);});
    }
    @Test void missingOpenidOrDisabledIdentityFailsStartup()throws Exception {
        runner(true,false,false).run(ctx->assertThat(ctx).hasFailed());
        runner(true,false).withPropertyValues("product.identity.enabled=false").run(ctx->assertThat(ctx).hasFailed());
        runner(true,false).withPropertyValues("product.identity.audience=https://foreign.example/v2").run(ctx->assertThat(ctx).hasFailed());
    }
    @Test void deploymentCannotBePickedUpByComponentScanning() {
        assertThat(EnforcementControlledReadDeployment.class.getAnnotation(Component.class)).isNull();
        assertThat(EnforcementControlledReadDeployment.class.getAnnotation(org.springframework.context.annotation.Configuration.class)).isNull();
        var imports=cn.xinji.agentplatform.security.product.ProductConsultationApplication.class
            .getAnnotation(org.springframework.context.annotation.Import.class);
        assertThat(imports==null?new Class<?>[0]:imports.value()).doesNotContain(EnforcementControlledReadDeployment.class);
    }
}
