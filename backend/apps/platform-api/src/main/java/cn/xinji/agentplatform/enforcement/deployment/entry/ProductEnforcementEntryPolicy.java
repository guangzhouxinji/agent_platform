package cn.xinji.agentplatform.enforcement.deployment.entry;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.util.UriUtils;

/** Deployment admission only; current domain authorization and source checks are never replaced. */
public final class ProductEnforcementEntryPolicy {
    public static final Set<String> ELIGIBLE = Set.of("job-pack", "document-templates", "document-rules", "legal-references");
    private final Set<String> enabled;
    private final Set<String> assets;
    public ProductEnforcementEntryPolicy(Collection<String> pages, Collection<String> assets) {
        if (pages == null || pages.isEmpty() || pages.size() != new HashSet<>(pages).size()
                || !ELIGIBLE.containsAll(pages) || !pages.contains("job-pack")
                || (pages.contains("document-rules") && !pages.contains("document-templates")))
            throw new IllegalArgumentException("EXPLICIT_ENFORCEMENT_PAGE_ADMISSION_REQUIRED");
        this.enabled = Set.copyOf(pages);
        if (assets == null || assets.stream().anyMatch(p -> !p.matches("assets/[A-Za-z0-9_./-]+")
                || Arrays.stream(p.split("/")).anyMatch(s -> s.equals(".") || s.equals("..") || s.isEmpty())))
            throw new IllegalArgumentException("EXACT_OWNED_ASSETS_REQUIRED");
        this.assets = Set.copyOf(assets);
    }
    public boolean allows(String method, String rawPath) {
        final String path;
        try { path = UriUtils.decode(rawPath, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { return false; }
        if (path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0
                || path.indexOf(0) >= 0 || path.contains("//")
                || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.equals(".") || p.equals(".."))) return false;
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (Set.of("/", "/app", "/app/", "/app/enforcement", "/app/enforcement/", "/app/enforcement.html").contains(path)) return read;
        if (path.startsWith("/app/assets/")) return read && assets.contains(path.substring("/app/".length()));
        for (String page : enabled) if (path.equals("/app/enforcement/" + page)) return read;
        // These protocols keep their existing Spring security and maintenance proofs.
        if (prefix(path,"/v2/identity") || prefix(path,"/oauth2") || prefix(path,"/login")
                || prefix(path,"/.well-known")) return true;
        if (enabled.contains("job-pack") && prefix(path,"/v2/enforcement/job-packs")) return true;
        if (enabled.contains("document-templates") && prefix(path,"/v2/enforcement/document-templates")) return true;
        if (enabled.contains("document-rules") && prefix(path,"/v2/enforcement/document-rules")) return true;
        return enabled.contains("legal-references") && (prefix(path,"/v2/enforcement/legal-basis") || prefix(path,"/v2/enforcement/legal-references"));
    }
    private static boolean prefix(String path, String base) { return path.equals(base) || path.startsWith(base+"/"); }
}
