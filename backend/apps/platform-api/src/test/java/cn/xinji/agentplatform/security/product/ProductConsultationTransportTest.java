package cn.xinji.agentplatform.security.product;

import jakarta.servlet.FilterChain;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Route-by-route transport contract; mocks here do not prove a live authority or TLS handshake. */
class ProductConsultationTransportTest {
    static Stream<String> routes() {
        String base="/v2/consultation/tasks/00000000-0000-4000-8000-000000000001";
        String run="00000000-0000-4000-8000-000000000002";
        return Stream.of("POST "+base+"/agent-runs", "GET "+base+"/agent-runs/"+run,
                "POST "+base+"/agent-runs/"+run+"/advance", "GET "+base+"/retrieval-workspace",
                "POST "+base+"/retrievals/execute", "POST "+base+"/dialogue/proposals",
                "POST "+base+"/dialogue/explanations", "GET "+base+"/dialogue/"+run);
    }
    static MockHttpServletRequest request(String route,boolean secure) {
        var parts=route.split(" ",2);var request=new MockHttpServletRequest(parts[0],parts[1]);
        request.setSecure(secure);return request;
    }
    @org.junit.jupiter.api.Test
    void encodedOrBackslashTaskUriFailsBeforeDownstream() throws Exception {
        for(String path: new String[]{"/v2/consultation/tasks/%30/agent-runs", "/v2/consultation/tasks/x\\y/agent-runs"}) {
            var watermark=mock(ProductGrantWatermark.class);var chain=mock(FilterChain.class);
            var request=new MockHttpServletRequest("POST",path);request.setSecure(true);
            var response=new MockHttpServletResponse();
            new ProductIdentityTransportFilter(watermark).doFilter(request,response,chain);
            assertEquals(403,response.getStatus());verifyNoInteractions(watermark,chain);
        }
    }
    @org.junit.jupiter.api.Test
    void futureTaskRouteCannotSilentlyDropTransportGuardButOtherDomainsAreUnchanged() throws Exception {
        var watermark=mock(ProductGrantWatermark.class);var chain=mock(FilterChain.class);
        var filter=new ProductIdentityTransportFilter(watermark);var denied=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/v2/consultation/tasks/x/future-route"),denied,chain);
        assertEquals(403,denied.getStatus());verifyNoInteractions(chain,watermark);
        var other=new MockHttpServletRequest("GET","/v2/adjudication/cases");var response=new MockHttpServletResponse();
        filter.doFilter(other,response,chain);verify(chain).doFilter(other,response);verifyNoInteractions(watermark);
    }
    @ParameterizedTest @MethodSource("routes")
    void insecureRequestNeverReachesAuthorityOrController(String route) throws Exception {
        var watermark=mock(ProductGrantWatermark.class);var chain=mock(FilterChain.class);
        var response=new MockHttpServletResponse();
        new ProductIdentityTransportFilter(watermark).doFilter(request(route,false),response,chain);
        assertEquals(403,response.getStatus());verifyNoInteractions(watermark,chain);
    }
    @ParameterizedTest @MethodSource("routes")
    void secureRequestRequiresCurrentIndependentAuthority(String route) throws Exception {
        var watermark=mock(ProductGrantWatermark.class);var chain=mock(FilterChain.class);
        var request=request(route,true);var response=new MockHttpServletResponse();
        new ProductIdentityTransportFilter(watermark).doFilter(request,response,chain);
        assertEquals(200,response.getStatus());verify(watermark).assertCurrent();verify(chain).doFilter(request,response);
    }
    @ParameterizedTest @MethodSource("routes")
    void unresolvedAuthorityFailsClosedBeforeController(String route) throws Exception {
        var watermark=mock(ProductGrantWatermark.class);var chain=mock(FilterChain.class);
        doThrow(new IllegalStateException("Unresolved authority intent")).when(watermark).assertCurrent();
        var response=new MockHttpServletResponse();
        new ProductIdentityTransportFilter(watermark).doFilter(request(route,true),response,chain);
        assertEquals(503,response.getStatus());verifyNoInteractions(chain);
    }
}
