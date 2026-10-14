package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class ProductTrademarkLibraryHttpTest {
    @TempDir Path root;
    AnnotationConfigWebApplicationContext context;
    ProductTrademarkReadFixture f;
    MockMvc mvc;
    static final String ROUTE="/v2/consultation/tasks/task/trademarks";
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("trademark-read-http-isolated");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",Map.of(
                "product.consultation.trademark-read.enabled","true","product.identity.enabled","true",
                "product.source.engine.enabled","true","product.identity.issuer",ProductTrademarkReadFixture.ISSUER,"test.trademark.root",root.toString())));
        context.register(Web.class);context.refresh();f=context.getBean(ProductTrademarkReadFixture.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new ProductIdentityTransportFilter(f.grants),context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void actualJwtSqlPageAndExactVersionHttpHaveNoStoreWithoutCertificate() throws Exception {
        var response=mvc.perform(get(ROUTE).secure(true).header("Authorization","Bearer "+f.token).header("X-Request-Id","query-1")).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        var page=f.json.readTree(response.getContentAsByteArray());assertThat(page.path("data").path("pagination").path("reliableTotal").asLong()).isEqualTo(21);
        var ref=page.path("data").path("items").get(0).path("ref");
        var detail=mvc.perform(get(ROUTE+"/sources/source/records/"+ref.path("recordId").asText()).secure(true)
                .param("version",ref.path("version").asText()).header("Authorization","Bearer "+f.token).header("X-Request-Id","detail-1")).andReturn().getResponse();
        assertThat(detail.getStatus()).withFailMessage(detail.getContentAsString()).isEqualTo(200);
        assertThat(f.json.readTree(detail.getContentAsByteArray()).path("data").path("item").path("registrationNumber").asText()).isEqualTo("00012A");
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","http","bad-token","session","service"})
    void currentTransportAndHumanIdentityCannotBeBypassed(String fault) throws Exception {
        if(fault.equals("session"))f.db.update("UPDATE id2_session SET status='REVOKED'");
        if(fault.equals("service"))f.db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL WHERE subject_id='reader'");
        var request=get(ROUTE).secure(!fault.equals("http")).header("X-Request-Id","read");
        if(!fault.equals("anonymous"))request.header("Authorization","Bearer "+(fault.equals("bad-token")?"bad-token":f.token));
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(fault.equals("http")||fault.equals("service")?403:401);
        assertThat(response.getContentAsString()).doesNotContain("Brand",f.token,"SELECT ",root.toString());
    }
    @Test void configurationAcceptsRealPinnedArrayAndRejectsInvalidArrays() throws Exception {
        String encoded=f.json.writeValueAsString(java.util.List.of(f.origin.pin));
        assertThat(ProductTrademarkLibraryConfiguration.pins(f.json,encoded)).containsExactly(f.origin.pin);
        for(String bad:java.util.List.of("[]",encoded+" []","[{\"sourceId\":\"x\",\"sourceId\":\"y\"}]","{}"))
            assertThatThrownBy(()->ProductTrademarkLibraryConfiguration.pins(f.json,bad)).isInstanceOf(RuntimeException.class);
    }
    @Test void productionBeanAssemblyReadsUnicodeAndSymbolRecordThroughFixedRoute() throws Exception {
        assertThat(context.getBean(ProductTrademarkLibraryConfiguration.Assembly.class)).isNotSameAs(f.assembly);
        String record="商标 /+%?#&= 20";
        String version=f.db.queryForObject("SELECT source_version FROM kb_record_version WHERE stable_record_key=?",String.class,record);
        assertThat(version).startsWith("RAW_SHA256:");
        var response=mvc.perform(get(ROUTE+"/records/detail").secure(true).queryParam("sourceId","source")
                .queryParam("recordId",record).queryParam("version",version).queryParam("refresh","true")
                .header("Authorization","Bearer "+f.token).header("X-Request-Id","unicode-detail")).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        var ref=f.json.readTree(response.getContentAsByteArray()).path("data").path("item").path("ref");
        assertThat(ref.path("sourceId").asText()).isEqualTo("source");
        assertThat(ref.path("recordId").asText()).isEqualTo(record);
        assertThat(ref.path("version").asText()).isEqualTo(version);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-sourceId","duplicate-recordId","duplicate-version","duplicate-refresh","missing-sourceId","missing-recordId","missing-version","blank-recordId","invalid-refresh"})
    void ambiguousOrIncompleteDetailCoordinatesAreRejected(String fault) throws Exception {
        var request=get(ROUTE+"/records/detail").secure(true).header("Authorization","Bearer "+f.token).header("X-Request-Id","invalid-detail");
        for(String key:java.util.List.of("sourceId","recordId","version","refresh")) {
            if(fault.equals("missing-"+key))continue;
            String value=key.equals("sourceId")?"source":key.equals("recordId")?"r00":key.equals("refresh")?"false":"exact-version";
            if(fault.equals("blank-"+key))value=" ";
            if(fault.equals("invalid-"+key))value="yes";
            request.queryParam(key,value);
            if(fault.equals("duplicate-"+key))request.queryParam(key,value);
        }
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("INVALID_REQUEST").doesNotContain("Brand");
    }
    @org.springframework.boot.test.context.TestConfiguration @Profile("trademark-read-http-isolated")
    @EnableWebMvc @EnableWebSecurity @Import({ProductTrademarkRouteSecurityConfiguration.class,ProductTrademarkLibraryConfiguration.class})
    static class Web {
        @Bean ProductTrademarkReadFixture fixture(ConfigurableEnvironment environment) throws Exception {
            var fixture=new ProductTrademarkReadFixture(Path.of(environment.getRequiredProperty("test.trademark.root")),false,true);
            environment.getPropertySources().addFirst(new MapPropertySource("approval-pin",Map.of("product.consultation.trademark-read.approval-pins-json",fixture.json.writeValueAsString(java.util.List.of(fixture.origin.pin)))));
            return fixture;
        }
        @Bean JwtDecoder jwtDecoder(ProductTrademarkReadFixture fixture){return fixture.decoder;}
        @Bean org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(ProductTrademarkReadFixture fixture){return fixture.db;}
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(ProductTrademarkReadFixture fixture){return fixture.manager;}
        @Bean ProductGrantWatermark grants(ProductTrademarkReadFixture fixture){return fixture.grants;}
        @Bean ProductConsultationAccess access(ProductTrademarkReadFixture fixture){return new ProductConsultationAccess(fixture.db,fixture.grants,ProductTrademarkReadFixture.ISSUER,java.time.Clock.systemUTC());}
        @Bean ProductSourceEvidenceFiles files(ProductTrademarkReadFixture fixture){return fixture.origin.files;}
        @Bean com.fasterxml.jackson.databind.ObjectMapper mapper(ProductTrademarkReadFixture fixture){return fixture.json;}
        @Bean ProductTrademarkLibraryController controller(ProductTrademarkLibraryConfiguration.Assembly reads){return new ProductTrademarkLibraryController(reads);}
    }
}
