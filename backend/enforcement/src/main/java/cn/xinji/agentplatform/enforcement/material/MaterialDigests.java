package cn.xinji.agentplatform.enforcement.material;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MaterialDigests {
    private MaterialDigests() { }

    static String hash(String... fields) {
        return hash(String.join("\u0000", fields).getBytes(StandardCharsets.UTF_8));
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
