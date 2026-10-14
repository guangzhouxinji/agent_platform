package cn.xinji.agentplatform.security.product;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed only inside the prompt security chain, never registered as a global servlet filter. */
final class ProductPromptTransportFilter extends OncePerRequestFilter {
    private final ProductGrantWatermark watermark;
    ProductPromptTransportFilter(ProductGrantWatermark watermark){this.watermark=watermark;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException{
        response.setHeader("Cache-Control","no-store");
        if(!request.isSecure()||request.getRequestURI().indexOf('%')>=0||request.getRequestURI().indexOf('\\')>=0){
            response.setStatus(403);return;
        }
        try{watermark.assertCurrent();}catch(RuntimeException failure){response.setStatus(503);return;}
        chain.doFilter(request,response);
    }
}
