package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic receipt text checks; no persistence or external authority. */
final class ProductComparisonStartReceiptChecks {
    private static final ObjectMapper json = new ObjectMapper();
    private ProductComparisonStartReceiptChecks() { }
    static JsonNode tree(String text) { try { return json.readTree(text); } catch (Exception bad) { throw conflict(); } }
    static String digest(String text) {
        if (text == null) throw conflict();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void jsonCoordinates(JsonNode node, ProductComparisonStartCommands.Command c, String report) {
        if (!c.scope().task().equals(node.path("taskId").asText()) || !c.scope().comparison().equals(node.path("comparisonId").asText())
                || !c.scope().candidate().equals(node.path("candidateId").asText()) || !c.scope().inputSha().equals(node.path("inputSha").asText())
                || !c.attemptId().equals(node.path("attemptId").asText()) || c.fencingToken() != node.path("token").asLong()
                || !report.equals(node.path("reportId").asText())) throw conflict();
    }
    private static IllegalStateException conflict() {
        return new IllegalStateException("COMPARISON_START_BINDING_OR_STATE_CONFLICT");
    }
}
