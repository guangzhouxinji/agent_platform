package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact JSON-string-to-original-byte mapping; admission/current authorization remain caller-owned. */
final class ProductPatentClaimOriginal implements OriginalClaimText {
    private final byte[] bytes;
    private final String ref, sha, version, pointer, text;
    private final int[] boundaries;

    ProductPatentClaimOriginal(String ref, byte[] original, String sha, String version,
                              String field, ObjectMapper mapper) {
        if (field == null || !Set.of("claims", "claimsText").contains(field)
                || ref == null || !ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}") || ref.contains("..")
                || original == null || !ProductCapturedArchive.digest(original).equals(sha)
                || !("RAW_SHA256:" + sha).equals(version)) throw new IllegalArgumentException("ORIGINAL_BINDING_INVALID");
        this.bytes = original.clone(); this.ref = ref; this.sha = sha; this.version = version;
        this.pointer = "/" + field.replace("~", "~0").replace("/", "~1");
        try {
            var root = mapper.readTree(bytes); var range = ProductCapturedJsonFields.ranges(bytes, mapper).get(field);
            if (range == null || !root.path(field).isTextual() || root.path(field).asText().isBlank()
                    || bytes[range[1]] != '"' || bytes[range[2] - 1] != '"')
                throw new IllegalArgumentException("CLAIMS_ORIGINAL_UNAVAILABLE");
            this.text = root.path(field).asText(); this.boundaries = new int[text.length() + 1];
            Arrays.fill(boundaries, -1); StringBuilder decoded = new StringBuilder();
            int cursor = range[1] + 1, stop = range[2] - 1;
            while (cursor < stop) {
                int start = cursor; String unit;
                if (bytes[cursor] == '\\') {
                    char escape = (char) bytes[++cursor]; cursor++;
                    if (escape == 'u') {
                        char first = unicode(cursor); cursor += 4;
                        if (Character.isHighSurrogate(first)) {
                            if (cursor + 6 > stop || bytes[cursor] != '\\' || bytes[cursor + 1] != 'u')
                                throw new IllegalArgumentException("ORIGINAL_UNICODE_INVALID");
                            char second = unicode(cursor + 2); cursor += 6;
                            if (!Character.isLowSurrogate(second)) throw new IllegalArgumentException("ORIGINAL_UNICODE_INVALID");
                            unit = new String(new char[]{first, second});
                        } else {
                            if (Character.isLowSurrogate(first)) throw new IllegalArgumentException("ORIGINAL_UNICODE_INVALID");
                            unit = String.valueOf(first);
                        }
                    } else unit = switch (escape) {
                        case '"' -> "\""; case '\\' -> "\\"; case '/' -> "/";
                        case 'b' -> "\b"; case 'f' -> "\f"; case 'n' -> "\n";
                        case 'r' -> "\r"; case 't' -> "\t";
                        default -> throw new IllegalArgumentException("ORIGINAL_ESCAPE_INVALID");
                    };
                } else {
                    int first = bytes[cursor] & 255;
                    int length = first < 128 ? 1 : (first & 224) == 192 ? 2 : (first & 240) == 224 ? 3 : 4;
                    if (cursor + length > stop) throw new IllegalArgumentException("ORIGINAL_UTF8_INVALID");
                    unit = StandardCharsets.UTF_8.newDecoder().decode(
                            java.nio.ByteBuffer.wrap(bytes, cursor, length)).toString(); cursor += length;
                }
                boundaries[decoded.length()] = start; decoded.append(unit); boundaries[decoded.length()] = cursor;
            }
            if (!decoded.toString().equals(text)) throw new IllegalArgumentException("ORIGINAL_TEXT_MAPPING_DIFFERS");
        } catch (java.io.IOException | IndexOutOfBoundsException invalid) {
            throw new IllegalArgumentException("CLAIMS_ORIGINAL_INVALID", invalid);
        }
    }

    private char unicode(int offset) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit((char) bytes[offset + i], 16);
            if (digit < 0) throw new IllegalArgumentException("ORIGINAL_UNICODE_INVALID"); value = value * 16 + digit;
        }
        return (char) value;
    }

    public String text() { return text; }
    boolean matches(Evidence candidate) {
        if (candidate == null) return false;
        int start = -1, end = -1;
        for (int i = 0; i < boundaries.length; i++) {
            if (boundaries[i] >= 0 && boundaries[i] == candidate.byteStart()) start = i;
            if (boundaries[i] >= 0 && boundaries[i] == candidate.byteEnd()) end = i;
        }
        return start >= 0 && end > start && evidence(start, end).equals(candidate);
    }
    public Evidence evidence(int start, int end) {
        if (start < 0 || end <= start || end > text.length() || boundaries[start] < 0 || boundaries[end] < 0)
            throw new IllegalArgumentException("ORIGINAL_SPAN_INVALID");
        int byteStart = boundaries[start], byteEnd = boundaries[end];
        return new Evidence(version, ref, sha, pointer, byteStart, byteEnd,
                ProductCapturedArchive.digest(Arrays.copyOfRange(bytes, byteStart, byteEnd)), text.substring(start, end));
    }
}
