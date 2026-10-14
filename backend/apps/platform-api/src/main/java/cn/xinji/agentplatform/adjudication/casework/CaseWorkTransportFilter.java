package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** TLS, bounded JSON, independent authority before work and before releasing any response bytes. */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CaseWorkTransportFilter extends OncePerRequestFilter {
    private final ProductGrantWatermark grants;
    private final CaseSourceAuthority sources;
    public CaseWorkTransportFilter(ProductGrantWatermark grants,CaseSourceAuthority sources){this.grants=grants;this.sources=sources;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/v2/adjudication/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(!request.isSecure() || request.getRequestURI().contains("%") || request.getRequestURI().contains("\\")) {response.setStatus(403);return;}
        byte[] body=request.getInputStream().readNBytes(1_048_577);
        if(body.length>1_048_576){response.setStatus(413);return;}
        var bounded=new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream(){
                    @Override public boolean isFinished(){return input.available()==0;}
                    @Override public boolean isReady(){return true;}
                    @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON only");}
                    @Override public int read(){return input.read();}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        };
        long generation,sourceGeneration;
        try{generation=grants.currentGeneration();sourceGeneration=sources.generation();}
        catch(RuntimeException failure){response.setStatus(503);return;}
        var buffered=new ContentCachingResponseWrapper(response);
        chain.doFilter(bounded,buffered);
        if(request.getRequestURI().startsWith("/v2/adjudication/case-works")){
            try{grants.assertUnchanged(generation);sources.assertUnchanged(sourceGeneration);}
            catch(RuntimeException failure){buffered.resetBuffer();buffered.setStatus(503);}
        }
        if(buffered.getContentSize()>8_388_608){buffered.resetBuffer();buffered.setStatus(413);}
        buffered.copyBodyToResponse();
    }
}
