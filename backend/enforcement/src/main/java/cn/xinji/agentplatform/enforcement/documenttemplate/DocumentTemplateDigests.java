package cn.xinji.agentplatform.enforcement.documenttemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class DocumentTemplateDigests {
    private DocumentTemplateDigests() { }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
