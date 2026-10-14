package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Per-digest scratch space; preserves the original UTF-16-length/UTF-8 byte framing. */
final class ProductHistoryDigestFraming {
    private static final byte[] NULL = {'-', '1', ':'};
    private final MessageDigest hash;
    private final byte[] prefix = new byte[12];

    ProductHistoryDigestFraming(MessageDigest hash) {
        this.hash = java.util.Objects.requireNonNull(hash);
        prefix[11] = ':';
    }

    void add(String value) {
        if (value == null) {
            hash.update(NULL);
            return;
        }
        int length = value.length(), start = 11;
        do {
            prefix[--start] = (byte) ('0' + length % 10);
            length /= 10;
        } while (length > 0);
        hash.update(prefix, start, 12 - start);
        hash.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
