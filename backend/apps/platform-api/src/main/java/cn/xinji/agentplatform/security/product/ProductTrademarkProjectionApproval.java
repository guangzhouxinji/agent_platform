package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.require;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Interprets a fixed installation-approved action; it never issues an approval. */
final class ProductTrademarkProjectionApproval {
    static final List<String> OBJECTS = List.of("RAW", "BINDING", "INDEX", "RELATION", "RECEIPT");
    private static final Set<String> STRINGS = Set.of("operation", "approvalRef", "version", "responsibleParty",
            "decidedAt", "evidenceRef", "evidenceSHA", "subject", "certificateSHA", "organization", "taskId",
            "scopeId", "sourceId", "contractVersion", "registeredSourceVersion", "verificationId", "manifestSHA",
            "recordsSHA", "inventorySHA", "bindingSetSHA", "mapId", "mapVersion", "mapSHA", "mapEvidenceRef", "sourceNature",
            "natureEvidenceRef", "natureEvidenceSHA", "rightsEvidenceRef", "rightsEvidenceSHA", "retentionRef",
            "retentionEvidenceSHA", "backupRef", "backupEvidenceSHA", "recoveryRef", "recoveryEvidenceSHA",
            "primaryDatabaseIdentitySHA", "authorityDatabaseIdentitySHA", "notBefore", "validUntil",
            "retainUntil", "backupUntil", "recoveryUntil", "approvedScopeSHA");
    private static final Set<String> NUMBERS = Set.of("maintainerVersion", "taskAuthorizationVersion", "retentionVersion");
    record Pin(String sourceId, String contractVersion, String capabilityRef, String capabilitySHA,
               String approvalEvidenceRef, String approvalEvidenceSHA, String approvedScopeSHA) {
        Pin {
            identity(sourceId); identity(contractVersion); path(capabilityRef); path(approvalEvidenceRef);
            capabilitySHA = digestText(capabilitySHA); approvalEvidenceSHA = digestText(approvalEvidenceSHA);
            approvedScopeSHA = digestText(approvedScopeSHA);
        }
    }
    private final ProductTrademarkCaptureJson json;
    private final ObjectNode fields;
    private final String capabilitySHA;

    ProductTrademarkProjectionApproval(ObjectMapper mapper, ProductSourceEvidenceFiles files, Pin pin) {
        json = new ProductTrademarkCaptureJson(mapper);
        JsonNode capability = json.parse(files.verified(pin.capabilityRef(), pin.capabilitySHA()));
        exact(capability, Set.of("schema", "sourceNature", "natureEvidenceRef", "natureEvidenceSHA", "projectionApproval"));
        require("TM-PROJECTION-CAPABILITY-V1".equals(text(capability.get("schema"))));
        JsonNode approval = capability.get("projectionApproval");
        var names = new java.util.HashSet<>(STRINGS); names.addAll(NUMBERS); names.add("projectionObjects");
        exact(approval, names);
        STRINGS.forEach(key -> text(approval.get(key)));
        NUMBERS.forEach(key -> require(integer(approval.get(key)) > 0));
        fields = ((ObjectNode) approval).deepCopy(); capabilitySHA = pin.capabilitySHA();
        require("TRADEMARK_CAPTURE_PROJECT_V1".equals(value("operation")));
        STRINGS.stream().filter(key -> key.endsWith("SHA")).forEach(key ->
                require(value(key).equals(digestText(value(key)))));
        require(value("sourceId").equals(pin.sourceId()) && value("contractVersion").equals(pin.contractVersion()));
        require(value("evidenceRef").equals(pin.approvalEvidenceRef())
                && value("evidenceSHA").equals(pin.approvalEvidenceSHA())
                && value("approvedScopeSHA").equals(pin.approvedScopeSHA()));
        ObjectNode canonical = fields.deepCopy(); canonical.remove("approvedScopeSHA");
        require(json.canonicalSha(canonical).equals(pin.approvedScopeSHA()));
        objects(fields.get("projectionObjects"));
        for (String key : List.of("decidedAt", "notBefore", "validUntil", "retainUntil", "backupUntil", "recoveryUntil")) until(key);
        require(until("notBefore").isBefore(until("validUntil")) && !until("decidedAt").isAfter(until("notBefore")));
        for (String key : List.of("sourceNature", "natureEvidenceRef", "natureEvidenceSHA"))
            require(value(key).equals(text(capability.get(key))));
        JsonNode evidence = json.parse(files.verified(pin.approvalEvidenceRef(), pin.approvalEvidenceSHA()));
        exact(evidence, Set.of("schema", "scope"));
        require("TM-PROJECTION-APPROVAL-V1".equals(text(evidence.get("schema"))));
        ObjectNode scope = fields.deepCopy(); scope.remove(List.of("evidenceRef", "evidenceSHA", "approvedScopeSHA"));
        require(json.canonicalSha(scope).equals(json.canonicalSha(evidence.get("scope"))));
        files.verified(value("rightsEvidenceRef"), value("rightsEvidenceSHA"));
        policy(files, "RETENTION", "retentionRef", "retentionEvidenceSHA", "retainUntil");
        policy(files, "BACKUP", "backupRef", "backupEvidenceSHA", "backupUntil");
        policy(files, "RECOVERY", "recoveryRef", "recoveryEvidenceSHA", "recoveryUntil");
    }

    String value(String key) { return text(fields.get(key)); }
    long number(String key) { return integer(fields.get(key)); }
    Instant until(String key) { return time(value(key)); }
    String capabilitySHA() { return capabilitySHA; }
    String fingerprint() { return json.canonicalSha(fields); }
    void current(Instant now) {
        require(!now.isBefore(until("notBefore")));
        for (String key : List.of("validUntil", "retainUntil", "backupUntil", "recoveryUntil")) require(now.isBefore(until(key)));
    }
    private void policy(ProductSourceEvidenceFiles files, String purpose, String ref, String hash, String deadline) {
        JsonNode policy = json.parse(files.verified(value(ref), value(hash)));
        exact(policy, Set.of("schema", "purpose", "sourceId", "contractVersion", "organization", "taskId",
                "projectionObjects", "until", "sourceRef", "version", "responsibleParty", "decidedAt"));
        require("TM-PROJECTION-POLICY-V1".equals(text(policy.get("schema"))) && purpose.equals(text(policy.get("purpose"))));
        for (String key : List.of("sourceId", "contractVersion", "organization", "taskId")) require(value(key).equals(text(policy.get(key))));
        objects(policy.get("projectionObjects"));
        require(until(deadline).equals(time(text(policy.get("until")))));
        for (String key : List.of("sourceRef", "version", "responsibleParty")) text(policy.get(key));
        require(!time(text(policy.get("decidedAt"))).isAfter(until("notBefore")));
    }
    private static void objects(JsonNode node) {
        require(node != null && node.isArray() && node.size() == OBJECTS.size());
        for (int i = 0; i < OBJECTS.size(); i++) require(OBJECTS.get(i).equals(text(node.get(i))));
    }
    private static void exact(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject()); var actual = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add); require(actual.equals(fields));
    }
}
