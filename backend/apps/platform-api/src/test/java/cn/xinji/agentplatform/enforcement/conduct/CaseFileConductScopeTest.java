package cn.xinji.agentplatform.enforcement.conduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseFileConductScopeTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test void exactVersionAndSourcedScopeDistinguishMatchUnknownAndMismatch() throws Exception {
        var file = root.resolve("case.json");
        var adapter = new CaseFileConductScope(file, json);
        Files.writeString(file, """
                {"caseRef":"case","version":"1","organization":"org"}
                """);
        assertEquals(Applicability.UNKNOWN, adapter.verify("actor", input(), rule()).state());
        Files.writeString(file, """
                {"caseRef":"case","version":"1","organization":"org","conductScope":{
                  "scope":{"value":"scope","sourceRef":"case-scope","version":"1"},
                  "jurisdiction":{"value":"area","sourceRef":"case-area","version":"1"},
                  "procedure":{"value":"procedure","sourceRef":"case-procedure","version":"1"}}}
                """);
        assertEquals(new ScopeVerification(Applicability.MATCH, "case", "1"),
                adapter.verify("actor", input(), rule()));
        Files.writeString(file, """
                {"caseRef":"case","version":"1","organization":"org","conductScope":{
                  "scope":{"value":"scope","sourceRef":"case-scope","version":"1"},
                  "jurisdiction":{"value":"area","sourceRef":"case-area","version":"1"},
                  "procedure":{"value":"other","sourceRef":"case-procedure","version":"1"}}}
                """);
        assertEquals(Applicability.MISMATCH, adapter.verify("actor", input(), rule()).state());
        Files.writeString(file, """
                {"caseRef":"case","version":"2","organization":"org"}
                """);
        assertThrows(SecurityException.class, () -> adapter.verify("actor", input(), rule()));
        Files.delete(file);
        assertThrows(IllegalStateException.class, () -> adapter.verify("actor", input(), rule()));
    }

    private static Rule rule() {
        return new Rule("rule", 1, "org", Behavior.TRADEMARK_INFRINGEMENT, "ACTIVE",
                new Basis("basis", "1", "section", "VERIFIED", "scope", "area", "procedure"),
                List.of(new Element("fact", "fact", true)), List.of(), true, "review", "review", "author");
    }

    private static Input input() {
        return new Input("analysis", "org", "case", "1", "evidence", 1, "rule", 1,
                Behavior.TRADEMARK_INFRINGEMENT, "2026-09-14", null, List.of(), List.of(), List.of());
    }
}
