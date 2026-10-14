package cn.xinji.agentplatform.model.gateway;

import java.net.URI;

/** Central historical provider configuration, overridable without editing any domain adapter. */
public final class ModelProviderDefaults {
    private ModelProviderDefaults() { }
    public static URI deepSeekEndpoint() {
        String configured=System.getProperty("agent.model.deepseek.endpoint",System.getenv("DEEPSEEK_API_URL"));
        return approvedDeepSeekEndpoint(URI.create(configured==null || configured.isBlank() ? "https://api.deepseek.com/chat/completions" : configured));
    }
    public static URI approvedDeepSeekEndpoint(URI endpoint) {
        if (endpoint==null || !"https".equalsIgnoreCase(endpoint.getScheme()) || !"api.deepseek.com".equalsIgnoreCase(endpoint.getHost())
                || !"/chat/completions".equals(endpoint.getPath()) || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null
                || endpoint.getFragment()!=null || (endpoint.getPort()!=-1 && endpoint.getPort()!=443))
            throw new IllegalArgumentException("SUPPLIER_ENDPOINT_APPROVAL_REQUIRED");
        return endpoint;
    }
    public static String materialModel() {
        String configured=System.getProperty("agent.model.adjudication.material",System.getenv("ADJ_MATERIAL_MODEL"));
        return configured==null || configured.isBlank() ? "deepseek-flash" : configured;
    }
}
