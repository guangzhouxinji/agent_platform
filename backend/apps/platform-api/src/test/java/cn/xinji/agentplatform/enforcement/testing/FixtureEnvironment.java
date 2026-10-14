package cn.xinji.agentplatform.enforcement.testing;

import java.util.Map;

/** Test-only namespace separation: partial isolated configuration must never fall back to historical databases. */
public final class FixtureEnvironment {
    private FixtureEnvironment() { }
    public static String optional(String name, String prefix, Map<String,String> environment) {
        boolean isolated = environment.keySet().stream().anyMatch(key -> key.startsWith(prefix));
        return environment.get(isolated ? prefix + name : name);
    }
    public static String required(String name, String prefix, Map<String,String> environment) {
        String value = optional(name,prefix,environment);
        if(value == null || value.isBlank()) throw new IllegalStateException(prefix + name + " or explicit legacy fixture configuration is required; no partial fallback");
        return value;
    }
}
