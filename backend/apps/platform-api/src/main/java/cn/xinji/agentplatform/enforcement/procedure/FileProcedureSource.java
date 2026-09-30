package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Read-only versioned project acceptance input adapter; never asserts official provenance. */
public final class FileProcedureSource implements ProcedureSource {
    @FunctionalInterface public interface MaterialVerification {
        void require(String ref, String organization);
    }
    private final Path manifest;
    private final ObjectMapper json;
    private final MaterialVerification materials;
    public FileProcedureSource(Path manifest, ObjectMapper json, MaterialVerification materials) {
        this.manifest = manifest;
        this.json = json;
        this.materials = java.util.Objects.requireNonNull(materials);
    }
    private JsonNode root() {
        try {
            var root = json.readTree(Files.readAllBytes(manifest));
            if (!"enf-procedure-v2-d-input-v1".equals(root.path("schemaVersion").asText()) ||
                    !"PROJECT_AUTHORED_FICTIONAL".equals(root.path("origin").asText()))
                throw new SecurityException("Acceptance source contract unavailable");
            return root;
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Procedure source unavailable", invalid);
        }
    }
    @Override public CaseSnapshot caseSnapshot(String ref, String version) {
        var root = root();
        JsonNode node = root.path("caseInput");
        if (root.path("caseInputs").isArray()) {
            node = null;
            for (var candidate : root.path("caseInputs"))
                if (ref.equals(candidate.path("caseRef").asText()) &&
                        version.equals(candidate.path("caseVersion").asText())) node = candidate;
            if (node == null) throw new SecurityException("Exact case snapshot unavailable");
        }
        if (!ref.equals(node.path("caseRef").asText()) || !version.equals(node.path("caseVersion").asText()))
            throw new SecurityException("Exact case snapshot unavailable");
        var events = new ArrayList<Event>();
        for (var event : node.path("events")) {
            var pair = event.path("ref").asText().split("@", 2);
            if (pair.length != 2) throw new IllegalStateException("Unversioned procedure event");
            events.add(new Event(pair[0], pair[1], event.path("kind").asText(),
                    OffsetDateTime.parse(event.path("occurredAt").asText()).toLocalDate(),
                    event.path("source").asText()));
        }
        var materials = new ArrayList<String>();
        var scope = root.path("ruleCandidate").path("applicability");
        node.path("materials").forEach(value -> {
            String exact = value.asText();
            this.materials.require(exact, scope.path("organization").asText());
            materials.add(exact);
        });
        return new CaseSnapshot(ref, version, scope.path("organization").asText(),
                scope.path("region").asText(), node.path("procedure").asText(),
                node.path("stage").asText(), node.hasNonNull("applicableAt")
                        ? LocalDate.parse(node.path("applicableAt").asText())
                        : events.isEmpty() ? null : events.getFirst().date(),
                List.copyOf(events), List.copyOf(materials), root.path("inputId").asText());
    }
    @Override public Calendar calendar(String ref, String version) {
        var node = root().path("calendar");
        if (!ref.equals(node.path("ref").asText()) || !version.equals(node.path("version").asText()))
            throw new SecurityException("Exact calendar unavailable");
        var excluded = new ArrayList<LocalDate>();
        node.path("nonWorkingDates").forEach(value -> excluded.add(LocalDate.parse(value.asText())));
        return new Calendar(ref, version, LocalDate.parse(node.path("range").get(0).asText()),
                LocalDate.parse(node.path("range").get(1).asText()), List.copyOf(excluded),
                node.path("status").asText());
    }
}
