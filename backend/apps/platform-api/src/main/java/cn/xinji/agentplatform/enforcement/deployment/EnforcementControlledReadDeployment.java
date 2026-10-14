package cn.xinji.agentplatform.enforcement.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** Explicitly imported only by the enforcement entry point. No scan stereotype. */
@ConditionalOnProperty(name="enforcement.deployment.controlled-read.enabled",havingValue="true")
public final class EnforcementControlledReadDeployment {
    @Bean ControlledReadPolicy controlledReadPolicy(Environment env, ObjectMapper json,
                                                    RegisteredClientRepository clients) throws Exception {
        require(env,"product.identity.enabled","true");
        require(env,"enforcement.v2.enabled","true");
        require(env,"enforcement.v2.job-pack.public-candidates-path","");
        require(env,"enforcement.v2.cleanup.enabled","false");
        require(env,"server.address","127.0.0.1");
        require(env,"server.ssl.enabled","true");
        require(env,"server.forward-headers-strategy","none");
        require(env,"spring.sql.init.mode","never");
        require(env,"spring.flyway.enabled","false");
        require(env,"spring.liquibase.enabled","false");
        require(env,"spring.mvc.hiddenmethod.filter.enabled","false");
        var policy=ControlledReadPolicy.load(Path.of(env.getRequiredProperty(
                "enforcement.deployment.controlled-read.policy-file")),env.getRequiredProperty(
                "enforcement.deployment.controlled-read.policy-sha256"),json,Clock.systemUTC());
        if (!policy.issuer().equals(env.getRequiredProperty("product.identity.issuer")))
            throw new IllegalStateException("Controlled-read issuer mismatch");
        require(env,"product.identity.audience",policy.issuer()+"/v2");
        var client=clients.findByClientId(policy.clientId());
        if (client==null || !client.getRedirectUris().contains(policy.callbackUri()) || !client.getScopes().contains("openid")
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE)
                || !client.getClientAuthenticationMethods().equals(java.util.Set.of(ClientAuthenticationMethod.NONE))
                || !client.getClientSettings().isRequireProofKey()
                || client.getClientSettings().isRequireAuthorizationConsent()!=policy.allowConsent())
            throw new IllegalStateException("Existing public PKCE client does not match policy");
        return policy;
    }
    @Bean FilterRegistrationBean<ControlledReadFilter> controlledReadIngress(ControlledReadPolicy policy) {
        var registration=new FilterRegistrationBean<>(new ControlledReadFilter(policy,Clock.systemUTC()));
        registration.setName("enforcementControlledReadIngress");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setMatchAfter(false);
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }
    private static void require(Environment env,String name,String expected) {
        if (!expected.equals(env.getProperty(name)))
            throw new IllegalStateException("Controlled-read effective configuration mismatch: "+name);
    }
}
