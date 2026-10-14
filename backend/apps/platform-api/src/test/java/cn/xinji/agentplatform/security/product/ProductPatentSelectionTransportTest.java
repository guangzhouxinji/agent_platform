package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProductPatentSelectionTransportTest {
    @Test void selectionPostAndReadRequireSecureTransportAndCurrentWatermark() throws Exception {
        for (String method : new String[] {"POST", "GET"}) {
            ProductGrantWatermark watermark = mock(ProductGrantWatermark.class);
            ProductIdentityTransportFilter filter = new ProductIdentityTransportFilter(watermark);
            String id="00000000-0000-4000-8000-000000000001";
            String path="/v2/consultation/tasks/"+id+"/patent-searches/"+id+"/selections"
                    + (method.equals("GET") ? "/"+id : "");
            FilterChain chain=mock(FilterChain.class);
            var insecure=new MockHttpServletRequest(method,path);
            var refused=new MockHttpServletResponse();
            filter.doFilter(insecure,refused,chain);
            assertThat(refused.getStatus()).isEqualTo(403);
            verifyNoInteractions(chain,watermark);
            var secure=new MockHttpServletRequest(method,path);
            secure.setSecure(true);
            var accepted=new MockHttpServletResponse();
            filter.doFilter(secure,accepted,chain);
            verify(watermark).assertCurrent();
            verify(chain).doFilter(secure,accepted);
            doThrow(new IllegalStateException("Watermark unavailable")).when(watermark).assertCurrent();
            var unavailable=new MockHttpServletResponse();
            filter.doFilter(secure,unavailable,mock(FilterChain.class));
            assertThat(unavailable.getStatus()).isEqualTo(503);
        }
    }

    @Test void selectedOriginalGetRequiresSecureTransportAndCurrentWatermark() throws Exception {
        var watermark = mock(ProductGrantWatermark.class);
        var filter = new ProductIdentityTransportFilter(watermark);
        String path = "/v2/consultation/tasks/00000000-0000-4000-8000-000000000001"
                + "/documents/real-collected/US_GRANT:3986239/versions/sha256-v1/content";
        var chain = mock(FilterChain.class);
        var insecure = new MockHttpServletRequest("GET", path);
        var refused = new MockHttpServletResponse();
        filter.doFilter(insecure, refused, chain);
        assertThat(refused.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain, watermark);
        var secure = new MockHttpServletRequest("GET", path);
        secure.setSecure(true);
        filter.doFilter(secure, new MockHttpServletResponse(), chain);
        verify(watermark).assertCurrent();
        verify(chain).doFilter(eq(secure), any());
        doThrow(new IllegalStateException("Unavailable watermark")).when(watermark).assertCurrent();
        var unavailable = new MockHttpServletResponse();
        filter.doFilter(secure, unavailable, mock(FilterChain.class));
        assertThat(unavailable.getStatus()).isEqualTo(503);
    }
}
