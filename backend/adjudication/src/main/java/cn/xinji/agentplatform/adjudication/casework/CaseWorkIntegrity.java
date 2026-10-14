package cn.xinji.agentplatform.adjudication.casework;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;

/** Canonical UTF-8 framing and input integrity; no professional or authorization rules. */
final class CaseWorkIntegrity {
    private CaseWorkIntegrity() { }
    static void unique(List<String> ids) {
        if (new HashSet<>(ids).size() != ids.size()) throw invalid("DUPLICATE_ID");
    }
    static void positive(long n) { if (n < 1) throw invalid("POSITIVE_VERSION_REQUIRED"); }
    static boolean blank(String value) { return value == null || value.isBlank(); }
    static void text(String value) { if (blank(value)) throw invalid("NONBLANK_TEXT_REQUIRED"); }
    static void checkHash(Source source, String content) {
        if (!source.sha256().equals(sha256(content))) throw invalid("SOURCE_CONTENT_HASH_MISMATCH");
    }
    static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
    static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String digest(long inputVersion, String policy, List<Section> sections, List<Diagnostic> diagnostics) {
        var canonical = new StringBuilder(); frame(canonical, "ADJ_REPORT/v1"); frame(canonical, Long.toString(inputVersion)); frame(canonical, policy);
        for (var section : sections) {
            frame(canonical, "SECTION"); frame(canonical, section.id()); frame(canonical, section.content());
            for (var source : section.sources()) {
                frame(canonical, "SOURCE"); frame(canonical, source.id()); frame(canonical, source.version());
                frame(canonical, source.locator()); frame(canonical, source.sha256()); frame(canonical, source.kind().name());
            }
        }
        for (var d : diagnostics) { frame(canonical, "DIAGNOSTIC"); frame(canonical, d.code()); frame(canonical, d.objectId()); frame(canonical, Boolean.toString(d.blocking())); }
        return sha256(canonical.toString());
    }
    static void frame(StringBuilder out, String value) { out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value); }
}
