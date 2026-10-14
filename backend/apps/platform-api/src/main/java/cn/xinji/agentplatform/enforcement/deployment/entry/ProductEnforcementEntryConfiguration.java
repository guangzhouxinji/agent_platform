package cn.xinji.agentplatform.enforcement.deployment.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Imported only by the final enforcement main; missing or unbound assets fail startup. */
public final class ProductEnforcementEntryConfiguration {
    public interface Assets { InputStream open(String path) throws IOException; }
    @Bean ProductEnforcementEntryPolicy productEnforcementEntryPolicy(ObjectMapper json) throws Exception {
        return load(json, p -> ProductEnforcementEntryConfiguration.class.getResourceAsStream("/static/app/"+p));
    }
    static ProductEnforcementEntryPolicy load(ObjectMapper json, Assets assets) throws Exception {
        final com.fasterxml.jackson.databind.JsonNode manifest;
        try (var input=assets.open("enforcement-product-assets.json")) {
            if(input==null)throw new IllegalStateException("BOUND_ENFORCEMENT_ASSETS_REQUIRED");
            manifest=json.readTree(input);
        }
        if(!"ENFORCEMENT_PRODUCT_BUNDLE/v1".equals(manifest.path("contract").asText())
                || !"enforcement.html".equals(manifest.path("entry").asText())
                || !manifest.path("enabledPages").isArray() || !manifest.path("files").isArray())
            throw new IllegalStateException("EXACT_ENFORCEMENT_ASSET_CONTRACT_REQUIRED");
        var pages=new ArrayList<String>(); manifest.path("enabledPages").forEach(p->pages.add(p.asText()));
        var clients=manifest.path("clientBindings");
        client(clients,"VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID");
        client(clients,"VITE_ENFORCEMENT_PRODUCT_CLIENT_ID");
        var clientIds=new HashSet<String>();
        for(var value:clients)if(!clientIds.add(value.asText()))throw new IllegalStateException("DISTINCT_ENFORCEMENT_CLIENT_BINDINGS_REQUIRED");
        if(pages.contains("document-templates"))client(clients,"VITE_ENFORCEMENT_DOCUMENT_CLIENT_ID");
        if(pages.contains("document-rules"))client(clients,"VITE_ENFORCEMENT_DOCUMENT_RULE_CLIENT_ID");
        if(pages.contains("legal-references"))client(clients,"VITE_ENFORCEMENT_LEGAL_CLIENT_ID");
        var owned=new HashSet<String>(); boolean html=false,js=false;
        for(var file:manifest.path("files")) {
            String p=file.path("path").asText();
            if(!p.equals("enforcement.html")&&!p.matches("assets/[A-Za-z0-9_./-]+"))throw new IllegalStateException("OTHER_PRODUCT_ASSET");
            if(Arrays.stream(p.split("/")).anyMatch(s->s.equals(".")||s.equals("..")||s.isEmpty())||!owned.add(p))throw new IllegalStateException("UNSAFE_ASSET_PATH");
            try(var bytes=assets.open(p)) {
                if(bytes==null)throw new IllegalStateException("ENTRY_ASSET_MISSING");
                byte[] actual=bytes.readAllBytes();
                if(actual.length!=file.path("bytes").asLong(-1)
                        || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)).equals(file.path("sha256").asText()))
                    throw new IllegalStateException("ENTRY_ASSET_SHA_DRIFT");
            }
            html|=p.equals("enforcement.html");js|=p.endsWith(".js");
        }
        if(!html||!js)throw new IllegalStateException("BUILT_ENTRY_REQUIRED");
        owned.remove("enforcement.html");
        return new ProductEnforcementEntryPolicy(pages,owned);
    }
    private static void client(com.fasterxml.jackson.databind.JsonNode clients,String key) {
        if(!clients.path(key).asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"))
            throw new IllegalStateException("CURRENT_ENFORCEMENT_CLIENT_BINDING_REQUIRED");
    }
    @Bean FilterRegistrationBean<ProductEnforcementEntryFilter> productEnforcementIngress(ProductEnforcementEntryPolicy policy) {
        var bean=new FilterRegistrationBean<>(new ProductEnforcementEntryFilter(policy));
        bean.setName("enforcementProductEntryAdmission");bean.setOrder(Ordered.HIGHEST_PRECEDENCE+1);
        bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(true);
        bean.addUrlPatterns("/*");return bean;
    }
}
