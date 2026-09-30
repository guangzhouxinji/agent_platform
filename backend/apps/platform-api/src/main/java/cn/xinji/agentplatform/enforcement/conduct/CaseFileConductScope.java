package cn.xinji.agentplatform.enforcement.conduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

/** Reads the exact case snapshot; absent provenance never implies rule applicability. */
final class CaseFileConductScope implements ConductAnalysisService.CaseScope {
    private final Path file;
    private final ObjectMapper json;

    CaseFileConductScope(Path file, ObjectMapper json) {
        this.file = file;
        this.json = json;
    }

    @Override public ScopeVerification verify(String actor, Input input, Rule rule) {
        try {
            JsonNode caseFile = json.readTree(Files.readAllBytes(file));
            if (caseFile == null || !input.caseRef().equals(text(caseFile, "caseRef")) ||
                    !input.caseVersion().equals(text(caseFile, "version")) ||
                    !input.organization().equals(text(caseFile, "organization")))
                throw new SecurityException("Case snapshot mismatch");
            JsonNode scope = caseFile.path("conductScope");
            Basis basis = rule.basis();
            var result = compare(scope.path("scope"), basis.scope());
            result = combine(result, compare(scope.path("jurisdiction"), basis.jurisdiction()));
            result = combine(result, compare(scope.path("procedure"), basis.procedure()));
            return new ScopeVerification(result, input.caseRef(), input.caseVersion());
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Case scope source unavailable", unavailable);
        }
    }

    private static Applicability compare(JsonNode field, String expected) {
        String value = text(field, "value");
        if (value == null || text(field, "sourceRef") == null ||
                text(field, "version") == null || expected == null || expected.isBlank())
            return Applicability.UNKNOWN;
        return value.equals(expected) ? Applicability.MATCH : Applicability.MISMATCH;
    }

    private static Applicability combine(Applicability left, Applicability right) {
        if (left == Applicability.MISMATCH || right == Applicability.MISMATCH)
            return Applicability.MISMATCH;
        return left == Applicability.UNKNOWN || right == Applicability.UNKNOWN
                ? Applicability.UNKNOWN : Applicability.MATCH;
    }

    private static String text(JsonNode node, String key) {
        JsonNode field = node.path(key);
        return field.isTextual() && !field.textValue().isBlank() ? field.textValue() : null;
    }
}
