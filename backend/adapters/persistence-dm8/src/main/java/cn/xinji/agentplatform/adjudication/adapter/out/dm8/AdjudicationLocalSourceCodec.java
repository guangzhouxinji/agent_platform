package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class AdjudicationLocalSourceCodec {
    private AdjudicationLocalSourceCodec() {}

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte valueByte : digest) hex.append(String.format("%02x", valueByte));
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot calculate local adjudication source digest", failure);
        }
    }
}
