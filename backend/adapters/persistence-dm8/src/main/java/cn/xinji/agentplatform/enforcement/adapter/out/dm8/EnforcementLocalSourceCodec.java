package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class EnforcementLocalSourceCodec {
    private EnforcementLocalSourceCodec() {}

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot calculate enforcement source digest", failure);
        }
    }
}
