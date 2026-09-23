package cn.xinji.agentplatform.security.apikey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

final class TrustedClientRequest extends HttpServletRequestWrapper {
    private final Map<String, String> trustedHeaders;

    TrustedClientRequest(HttpServletRequest request, String principalRef, String organizationRef,
                         String clientId, String requestId) {
        super(request);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-v0-requester", principalRef);
        headers.put("x-v0-actor", principalRef);
        if (organizationRef != null && !organizationRef.isBlank()) {
            headers.put("x-v0-organization", organizationRef);
        }
        headers.put("x-api-client-id", clientId);
        headers.put("x-request-id", requestId);
        trustedHeaders = Map.copyOf(headers);
    }

    @Override public String getHeader(String name) {
        return trustedHeaders.getOrDefault(name.toLowerCase(), super.getHeader(name));
    }

    @Override public Enumeration<String> getHeaders(String name) {
        String trusted = trustedHeaders.get(name.toLowerCase());
        return trusted == null ? super.getHeaders(name) : Collections.enumeration(java.util.List.of(trusted));
    }

    @Override public Enumeration<String> getHeaderNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Enumeration<String> original = super.getHeaderNames();
        while (original.hasMoreElements()) names.add(original.nextElement());
        names.addAll(trustedHeaders.keySet());
        return Collections.enumeration(names);
    }
}
