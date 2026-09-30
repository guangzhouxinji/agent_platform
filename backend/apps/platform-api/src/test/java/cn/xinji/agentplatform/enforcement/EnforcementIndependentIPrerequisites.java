package cn.xinji.agentplatform.enforcement;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;

/** Test-only guard: a configured target must be physically distinct before I may run. */
public final class EnforcementIndependentIPrerequisites {
    private EnforcementIndependentIPrerequisites() { }

    public static void requireDualDm8(String businessUrl, String authorityUrl) {
        if (endpoint(businessUrl).equals(endpoint(authorityUrl)))
            throw new IllegalArgumentException("Business and authority must be distinct DM8 endpoints");
    }

    public static void requireRestoredDm8(String businessUrl, String authorityUrl,
                                           String restoredUrl, Path sourceRoot, Path restoredRoot)
            throws IOException {
        requireDualDm8(businessUrl, authorityUrl);
        var restored = endpoint(restoredUrl);
        if (restored.equals(endpoint(businessUrl)) || restored.equals(endpoint(authorityUrl)))
            throw new IllegalArgumentException("Restored DB must be a third DM8 endpoint");
        Path source = sourceRoot.toRealPath();
        Path target = restoredRoot.toRealPath();
        if (source.startsWith(target) || target.startsWith(source))
            throw new IllegalArgumentException("Restored file root must be separate from source root");
    }

    public static void requireFreshRecoveryGate(Path marker) {
        Path gate = marker.toAbsolutePath().normalize();
        if (!Files.isDirectory(gate.getParent()))
            throw new IllegalArgumentException("Recovery gate parent must exist");
        for (String suffix : new String[]{"", ".resume", ".restore-ready", ".withdrawn", ".verified"})
            if (Files.exists(Path.of(gate + suffix)))
                throw new IllegalArgumentException("Recovery gate must be fresh: " + suffix);
    }

    private static String endpoint(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:dm://"))
            throw new IllegalArgumentException("Dedicated DM8 JDBC URL required");
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535)
            throw new IllegalArgumentException("DM8 host and port required");
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"))
            host = "loopback";
        return host + ":" + uri.getPort();
    }
}
