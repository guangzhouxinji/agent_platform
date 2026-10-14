package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Shared production HTTP assembly over owned empty workspace data and the actual grant watermark. */
final class ProductPromptRegisterHttpFixture implements AutoCloseable {
    static final String ISSUER="https://register-engineering.invalid",AUDIENCE="https://register-api-engineering.invalid";
    final ProductPromptRegisterFixture f;
    final ProductGrantWatermark watermark;
    final JwtDecoder decoder;
    final ProductSigningKeys keys;
    final Map<String,Object> properties=new HashMap<>();
    private final List<AnnotationConfigWebApplicationContext> contexts=new ArrayList<>();

    ProductPromptRegisterHttpFixture(Path root,ProductComparisonStartMtlsEngineeringSupport.Tls tls)throws Exception{
        f=new ProductPromptRegisterFixture(root,tls);
        try{
            f.independent.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,ProductComparisonJson.hash(""));
            watermark=new ProductGrantWatermark(f.db,f.independent,ISSUER);
            byte[] master=new byte[32];new SecureRandom().nextBytes(master);
            String encoded=Base64.getEncoder().encodeToString(master);Arrays.fill(master,(byte)0);
            keys=new ProductSigningKeys(f.db,new TransactionTemplate(f.mainManager),ISSUER,encoded,Duration.ofMinutes(5),f.clock);
            decoder=new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys,f.db,watermark,ISSUER,AUDIENCE);
            properties.put("product.identity.enabled","true");properties.put("product.identity.issuer",ISSUER);
            properties.put("product.identity.audience",AUDIENCE);properties.put("product.consultation.prompt.enabled","true");
            properties.put("product.consultation.prompt.register-enabled","true");
            properties.put("product.consultation.prompt.evidence-root",root.toString());
            properties.put("product.consultation.prompt.bindings-ref",f.bindingsRef);
            properties.put("product.consultation.prompt.bindings-sha256",ProductCapturedArchive.digest(Files.readAllBytes(root.resolve(f.bindingsRef))));
            properties.put("product.consultation.prompt.register-approval-pins-json",f.mapper.writeValueAsString(List.of(
                    new ProductPromptRegisterApproval.Pin(f.workspaceId,f.registerRef,ProductCapturedArchive.digest(Files.readAllBytes(root.resolve(f.registerRef)))))));
            f.mainFault.clear();f.authorityFault.clear();
        }catch(Exception failure){f.close();throw failure;}
    }
    Map<String,Object> properties(){return properties;}
    AnnotationConfigWebApplicationContext openContext(ServletContext servletContext){
        var context=new AnnotationConfigWebApplicationContext();context.setServletContext(servletContext);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("register-owned-http",new HashMap<>(properties)));
        context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("registerHttpFixture",this));
        context.register(Node.class);
        try{context.refresh();contexts.add(context);return context;}
        catch(RuntimeException failure){context.close();throw failure;}
    }
    String signedToken(){
        String kid=keys.rotate("owned-http-maintainer"),sid=UUID.randomUUID().toString(),jti=UUID.randomUUID().toString();
        String member="owned-http-member";
        f.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,?)",
                ISSUER,member,member,Timestamp.from(f.now));
        String token=new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer(ISSUER).subject(member)
                .audience(List.of(AUDIENCE)).issuedAt(f.now).notBefore(f.now.minusSeconds(1)).expiresAt(f.now.plusSeconds(600))
                .id(jti).claim("sid",sid).build())).getTokenValue();
        f.db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)",sid,jti,ISSUER,member,"owned-http-client",kid,
                Timestamp.from(f.now),Timestamp.from(f.now.plusSeconds(600)),"ACTIVE",null);
        decoder.decode(token);return token;
    }
    @Override public void close(){for(var context:contexts)context.close();f.close();}

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
    @Import({ProductPromptRegisterConfiguration.class,ProductPromptRegisterController.class,
            ProductIdentityRouteSecurityConfiguration.class,ProductIdentityTransportFilter.class})
    static class Node implements WebMvcConfigurer {
        @Bean JdbcTemplate db(ProductPromptRegisterHttpFixture fixture){return fixture.f.db;}
        @Bean DataSourceTransactionManager manager(ProductPromptRegisterHttpFixture fixture){return fixture.f.mainManager;}
        @Bean ProductGrantWatermark watermark(ProductPromptRegisterHttpFixture fixture){return fixture.watermark;}
        @Bean JwtDecoder decoder(ProductPromptRegisterHttpFixture fixture){return fixture.decoder;}
        @Bean ObjectMapper mapper(ProductPromptRegisterHttpFixture fixture){return fixture.f.mapper;}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){
            converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)));
        }
    }
}
