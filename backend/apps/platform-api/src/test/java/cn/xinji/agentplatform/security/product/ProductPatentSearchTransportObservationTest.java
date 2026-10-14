package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class ProductPatentSearchTransportObservationTest {
    private static final String ID="00000000-0000-4000-8000-000000000001";
    private final AtomicLong nanos=new AtomicLong();
    private final List<String> events=new ArrayList<>();
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void measuresAuthorityAndCompleteDownstreamWithoutSensitiveInputs() throws Exception {
        var observation=observe(200);
        nanos.set(11); observation.authorityChecked();
        nanos.set(47); observation.downstreamReturned(); observation.close(); observation.close();
        assertEquals(1,events.size()); var event=mapper.readTree(events.getFirst());
        assertEquals("PATENT_SEARCH_TRANSPORT/v1",event.path("event").asText());
        assertEquals(11,event.path("authorityNanos").asLong());
        assertEquals(36,event.path("downstreamNanos").asLong());
        assertEquals(47,event.path("durationNanos").asLong());
        assertTrue(event.path("authorityChecked").asBoolean());
        assertTrue(event.path("downstreamReturned").asBoolean());
        assertEquals(200,event.path("status").asInt());
        assertEquals(11,event.size());
        assertTrue(event.path("timingId").asText().matches("[0-9a-f-]{36}"));
        assertNull(ProductPatentSearchTransportObservation.currentTimingId());
        for(String key:List.of("query","cursor","token","subject","cookie","exception","certificate"))
            assertFalse(event.has(key),key);
    }

    @Test void nestedDetailsAreRequestLocalAndExceptionsAreNotReplaced() throws Exception {
        assertNull(ProductPatentSearchTransportObservation.currentTimingId());
        try(var outer=observe(200)) {
            String outerId=ProductPatentSearchTransportObservation.currentTimingId();
            var failure=new IllegalStateException("original failure");
            assertSame(failure,assertThrows(IllegalStateException.class,()->
                    ProductPatentSearchTransportObservation.measure(ProductPatentSearchTransportObservation.Detail.SOURCE_INVENTORY,
                            ()->{ nanos.set(7);throw failure; })));
            try(var inner=observe(200)) {
                assertNotEquals(outerId,ProductPatentSearchTransportObservation.currentTimingId());
                ProductPatentSearchTransportObservation.measure(ProductPatentSearchTransportObservation.Detail.SOURCE_PROJECTION,
                        ()->{nanos.set(10);return true;});
            }
            assertEquals(outerId,ProductPatentSearchTransportObservation.currentTimingId());
        }
        assertNull(ProductPatentSearchTransportObservation.currentTimingId());
        assertEquals(3,mapper.readTree(events.get(0)).path("detailNanos").path("SOURCE_PROJECTION").asLong());
        var outer=mapper.readTree(events.get(1)).path("detailNanos");
        assertEquals(7,outer.path("SOURCE_INVENTORY").asLong());assertFalse(outer.has("SOURCE_PROJECTION"));
    }

    @Test void refusalDoesNotPretendDownstreamRan() throws Exception {
        try(var observation=observe(503)) { nanos.set(23); }
        var event=mapper.readTree(events.getFirst());
        assertFalse(event.path("authorityChecked").asBoolean());
        assertFalse(event.path("downstreamReturned").asBoolean());
        assertEquals(23,event.path("authorityNanos").asLong());
        assertEquals(0,event.path("downstreamNanos").asLong());
        assertEquals(503,event.path("status").asInt());
    }

    @Test void downstreamExceptionAndOutputFailureKeepTheOriginalException() throws Exception {
        var failure=new IllegalStateException("private-query-token");
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var observation=observe(200)) {
                nanos.set(5); observation.authorityChecked(); nanos.set(17); throw failure;
            }
        }));
        var event=mapper.readTree(events.getFirst());
        assertFalse(event.path("downstreamReturned").asBoolean());
        assertEquals(12,event.path("downstreamNanos").asLong());
        assertFalse(events.getFirst().contains("private"));
        assertSame(failure,assertThrows(IllegalStateException.class,()->{
            try(var observation=new ProductPatentSearchTransportObservation(ID,ID,nanos::get,()->200,
                    ignored->{throw new IllegalStateException("sink");})) { throw failure; }
        }));
        assertDoesNotThrow(()->new ProductPatentSearchTransportObservation(ID,ID,nanos::get,()->200,
                ignored->{throw new IllegalStateException("sink");}).close());
    }

    @Test void onlyExactExistingGetResultRouteIsObserved() {
        String path="/v2/consultation/tasks/"+ID+"/patent-searches/"+ID+"/results";
        for(var request:List.of(new MockHttpServletRequest("POST",path),
                new MockHttpServletRequest("GET",path+"/selections"),
                new MockHttpServletRequest("GET","/login"),
                new MockHttpServletRequest("GET",path.replace(ID,"%00"))))
            assertNull(ProductPatentSearchTransportObservation.start(request,new MockHttpServletResponse()));
        var request=new MockHttpServletRequest("GET",path);
        request.setQueryString("cursor=secret-query"); request.addHeader("Authorization","secret-token");
        var response=new MockHttpServletResponse();
        try(var observation=ProductPatentSearchTransportObservation.start(request,response)) {
            assertNotNull(observation);
        }
        assertThrows(IllegalArgumentException.class,()->new ProductPatentSearchTransportObservation(
                "bad\"\n",ID,nanos::get,()->200,events::add));
    }

    private ProductPatentSearchTransportObservation observe(int status) {
        return new ProductPatentSearchTransportObservation(ID,ID,nanos::get,()->status,events::add);
    }

    @Test void realWatermarkFilterKeepsTlsPendingAndDownstreamFailureSemantics() throws Exception {
        var primary=new org.h2.jdbcx.JdbcDataSource();
        primary.setURL("jdbc:h2:mem:timing-primary-"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        primary.setUser("sa");
        var independent=new org.h2.jdbcx.JdbcDataSource();
        independent.setURL("jdbc:h2:mem:timing-authority-"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        independent.setUser("sa");
        try(var connection=primary.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("db/dm8/V032__product_identity.sql"));
        }
        try(var connection=independent.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("db/identity-authority/V001__grant_watermark.sql"));
            connection.createStatement().execute("INSERT INTO id2_authority_watermark VALUES('https://issuer',0,'READY',NULL,'"
                    +"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"+"')");
        }
        var authority=new org.springframework.jdbc.core.JdbcTemplate(independent);
        var watermark=new ProductGrantWatermark(new org.springframework.jdbc.core.JdbcTemplate(primary),
                authority,"https://issuer");
        var filter=new ProductIdentityTransportFilter(watermark);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory
                .getLogger(ProductPatentSearchTransportObservation.class);
        var previous=logger.getLevel();
        var sink=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        sink.start(); logger.addAppender(sink); logger.setLevel(ch.qos.logback.classic.Level.INFO);
        var called=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var success=new MockHttpServletResponse();
            filter.doFilter(pageRequest(true),success,(request,response)->called.incrementAndGet());
            assertEquals(200,success.getStatus()); assertEquals(1,called.get());
            authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unfinished'");
            var pending=new MockHttpServletResponse();
            filter.doFilter(pageRequest(true),pending,(request,response)->called.incrementAndGet());
            assertEquals(503,pending.getStatus()); assertEquals(1,called.get());
            var insecure=new MockHttpServletResponse();
            filter.doFilter(pageRequest(false),insecure,(request,response)->called.incrementAndGet());
            assertEquals(403,insecure.getStatus()); assertEquals(1,called.get());
            authority.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL");
            var failure=new java.io.IOException("secret-downstream-exception");
            assertSame(failure,assertThrows(java.io.IOException.class,()->filter.doFilter(pageRequest(true),
                    new MockHttpServletResponse(),(request,response)->{throw failure;})));
            assertEquals(4,sink.list.size());
            var first=mapper.readTree(sink.list.get(0).getFormattedMessage());
            assertTrue(first.path("authorityChecked").asBoolean());
            assertTrue(first.path("downstreamReturned").asBoolean());
            assertEquals(503,mapper.readTree(sink.list.get(1).getFormattedMessage()).path("status").asInt());
            assertEquals(403,mapper.readTree(sink.list.get(2).getFormattedMessage()).path("status").asInt());
            assertFalse(mapper.readTree(sink.list.get(3).getFormattedMessage()).path("downstreamReturned").asBoolean());
            for(var event:sink.list) assertFalse(event.getFormattedMessage().contains("secret"));
            assertEquals(0,authority.queryForObject("SELECT generation FROM id2_authority_watermark",Long.class));
        } finally { logger.detachAppender(sink); sink.stop(); logger.setLevel(previous); }
    }

    private MockHttpServletRequest pageRequest(boolean secure) {
        var request=new MockHttpServletRequest("GET","/v2/consultation/tasks/"+ID
                +"/patent-searches/"+ID+"/results");
        request.setSecure(secure); request.setQueryString("cursor=secret-cursor");
        request.addHeader("Authorization","secret-token"); return request;
    }
}
