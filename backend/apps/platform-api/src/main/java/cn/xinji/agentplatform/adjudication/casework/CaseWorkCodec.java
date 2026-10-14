package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Scope;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Exact snapshot encoding, graph validation and body parsing are isolated from the shared mapper. */
public final class CaseWorkCodec {
    private final ObjectMapper mapper;
    public CaseWorkCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    public <T> T decode(String text, Class<T> type) {
        try { return mapper.readValue(text, type); }
        catch (Exception failed) { throw new IllegalStateException("PERSISTED_PAYLOAD_INVALID", failed); }
    }
    public <T> T request(JsonNode node, Class<T> type) {
        try { return mapper.treeToValue(node, type); }
        catch (Exception failed) { throw new IllegalArgumentException("INVALID_COMMAND_BODY", failed); }
    }
    public String encode(Object value) {
        try { return mapper.writeValueAsString(canonical(mapper.valueToTree(value), "")); }
        catch (Exception failed) { throw new IllegalStateException("PAYLOAD_ENCODING_FAILED", failed); }
    }
    private JsonNode canonical(JsonNode node, String field) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            for (var name : names) result.set(name, canonical(node.get(name), name)); return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode(); var items = new ArrayList<JsonNode>(); node.forEach(items::add);
            if (Set.of("contributors", "inputContributors", "allowedUses").contains(field)) items.sort(Comparator.comparing(JsonNode::asText));
            for (var item : items) result.add(canonical(item, "")); return result;
        }
        return node;
    }
    public List<Source> sources(State state) {
        var result = new ArrayList<Source>(); result.add(state.caseInput().source());
        state.materials().forEach(m -> result.add(m.source())); state.claims().forEach(c -> result.add(c.source()));
        state.citations().forEach(c -> result.add(c.source()));
        state.reports().forEach(r -> r.sections().forEach(s -> result.addAll(s.sources())));
        return result.stream().distinct().toList();
    }
    public void validate(State state, Scope scope) {
        if (!Scope.of(state.caseInput()).equals(scope) || state.audits().size() != state.version()) broken();
        if (state.inputVersion() < 1 || state.inputVersion() > state.version()) broken();
        for (int i = 0; i < state.audits().size(); i++) if (state.audits().get(i).version() != i + 1L) broken();
        var materials = new HashMap<String, Material>();
        for (var material : state.materials()) {
            if (!scope.caseId().equals(material.caseId()) || materials.putIfAbsent(material.id(), material) != null) broken();
            if (material.supersedesMaterialId() != null) {
                var prior = materials.get(material.supersedesMaterialId());
                if (prior == null || prior == material || prior.side() != material.side() || !prior.submitter().equals(material.submitter())) broken();
            }
        }
        var claims = new HashMap<Long, Claim>();
        for (var claim : state.claims()) {
            if (!scope.caseId().equals(claim.caseId()) || claim.revision() != claims.size() + 1L) broken();
            claims.put(claim.revision(), claim);
        }
        var findingIds = new HashSet<String>();
        for (var finding : state.findings()) {
            var claim = claims.get(finding.claimRevision()); var material = materials.get(finding.materialId());
            if (!findingIds.add(finding.id()) || !scope.caseId().equals(finding.caseId()) || claim == null || material == null
                    || !material.readable() || !material.content().contains(finding.exactExcerpt())
                    || claim.elements().stream().noneMatch(e -> e.id().equals(finding.elementId()))) broken();
        }
        var citationIds = new HashSet<String>();
        for (var citation : state.citations()) if (!scope.caseId().equals(citation.caseId()) || !citationIds.add(citation.id())
                || !Objects.equals(citation.region(), state.caseInput().region())
                || !Objects.equals(citation.procedure(), state.caseInput().procedure())
                || !Objects.equals(citation.relevantDate(), state.caseInput().relevantDate())) broken();
        var known = new HashSet<>(sourcesWithoutReports(state));
        for (int i = 0; i < state.reports().size(); i++) {
            var report = state.reports().get(i);
            if (report.revision() != i + 1L || report.inputVersion() > state.inputVersion()) broken();
            for (var section : report.sections()) if (!known.containsAll(section.sources()) && !(section.id().equals("case") && report.inputVersion() < state.inputVersion())) broken();
            if (report.reviewState() != ReviewState.DRAFT && !report.complete()) broken();
            for (var review : report.reviews()) if (!review.reportSha256().equals(report.contentSha256())
                    || report.contributors().contains(review.actor())) broken();
            boolean decided = report.reviewState() == ReviewState.RETURNED || report.reviewState() == ReviewState.REVIEWED_NOT_OFFICIAL;
            if (decided && (report.reviews().size() != 1 || report.reviews().getFirst().outcome() != report.reviewState())) broken();
            if (!decided && !report.reviews().isEmpty()) broken();
        }
        var byLocator = new HashMap<String, Source>();
        for (var source : sources(state)) {
            String key = source.id() + "\u0000" + source.version() + "\u0000" + source.locator();
            var prior = byLocator.putIfAbsent(key, source);
            if (prior != null && !prior.equals(source)) broken();
        }
    }
    private List<Source> sourcesWithoutReports(State state) {
        var sources = new ArrayList<Source>(); sources.add(state.caseInput().source());
        state.materials().forEach(m -> sources.add(m.source())); state.claims().forEach(c -> sources.add(c.source()));
        state.citations().forEach(c -> sources.add(c.source())); return sources;
    }
    public void transition(State previous, State next) {
        if (next.version() != previous.version() + 1 || next.inputVersion() < previous.inputVersion()
                || next.inputVersion() > previous.inputVersion() + 1 || !Scope.of(previous.caseInput()).equals(Scope.of(next.caseInput()))) broken();
        prefix(previous.materials(), next.materials()); prefix(previous.claims(), next.claims());
        prefix(previous.findings(), next.findings()); prefix(previous.citations(), next.citations()); prefix(previous.audits(), next.audits());
        if (next.reports().size() < previous.reports().size() || next.reports().size() > previous.reports().size() + 1) broken();
        for (int i = 0; i < previous.reports().size(); i++) {
            var before = previous.reports().get(i); var after = next.reports().get(i);
            if (!before.contentSha256().equals(after.contentSha256()) || !before.sections().equals(after.sections())
                    || !before.diagnostics().equals(after.diagnostics()) || !before.author().equals(after.author())
                    || !after.contributors().containsAll(before.contributors())) broken();
            if (before.reviewState() == ReviewState.RETURNED || before.reviewState() == ReviewState.REVIEWED_NOT_OFFICIAL)
                if (!before.equals(after)) broken();
        }
    }
    private <T> void prefix(List<T> old, List<T> current) {
        if (current.size() < old.size() || !current.subList(0, old.size()).equals(old)) broken();
    }
    public static void hash(String payload, String expected) { if (!sha256(payload).equals(expected)) broken(); }
    private static void broken() { throw new IllegalStateException("CASE_PERSISTENCE_INTEGRITY_FAILED"); }
}
