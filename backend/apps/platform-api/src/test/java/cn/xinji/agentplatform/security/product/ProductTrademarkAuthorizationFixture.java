package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.sha;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

final class ProductTrademarkAuthorizationFixture {
    final ProductTrademarkProjectionFixture capture = new ProductTrademarkProjectionFixture();
    final JdbcDataSource primary = database(), independent = database();
    final JdbcTemplate db = new JdbcTemplate(primary), authority = new JdbcTemplate(independent);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final Path root;
    final ProductTrademarkCapture.Input input;
    final ProductTrademarkCapture.VerifiedTrademarkCapture verified;
    final X509Certificate certificate = certificate();
    final String certSHA = sha(certificate.getEncoded());
    final ObjectNode approval = capture.json.createObjectNode();
    ProductTrademarkProjectionApproval.Pin pin;
    ProductSourceWatermark watermark;
    ProductSourceAvailability availability;
    ProductMaintenanceProof maintenance;
    ProductSourceEvidenceFiles files;
    ProductTrademarkSourceProjectionAuthority adapter;

    ProductTrademarkAuthorizationFixture(Path root) throws Exception {
        this(root,false);
    }
    ProductTrademarkAuthorizationFixture(Path root,boolean unicode) throws Exception {
        this.root = root;
        input=capture.input(21,detail->{},i->unicode&&i==20?"商标 /+%?#&= 20":String.format(java.util.Locale.ROOT,"r%02d",i));
        verified=new ProductTrademarkCaptureMapper(capture.json).map(input);
        for (String script : List.of("V032__product_identity.sql", "V034__product_consultation_initial_task.sql",
                "V036__product_source_candidates.sql", "V037__product_source_admission.sql", "V039__product_source_verification.sql",
                "V041__product_task_authorization_version.sql", "V043__product_task_cancel_epoch.sql", "V053__product_source_use_decision.sql"))
            script(primary, "db/dm8/" + script);
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        script(independent, "db/identity-authority/V003__consultation_source_watermark.sql");
        request.setSecure(true); request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
        authority.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) "
                + "VALUES(1,'maintainer','organization',?,'SOURCES','ACTIVE',1)", certSHA);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                + "VALUES('issuer','subject','subject','isolated','ACTIVE',1,?)", ts(ProductTrademarkProjectionFixture.NOW));
        db.update("INSERT INTO con3_organization(organization_id,name,status,version,created_at) VALUES('organization','Test','ACTIVE',1,?)", ts(ProductTrademarkProjectionFixture.NOW));
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,expires_at,retention_ref,retention_version,retention_evidence_sha,created_at,authorization_version,cancel_epoch) "
                + "VALUES('task','organization','issuer','subject','AWAITING_GOAL',1,?,'retention.json',1,?,?,7,0)",
                ts(ProductTrademarkProjectionFixture.UNTIL), "a".repeat(64), ts(ProductTrademarkProjectionFixture.NOW));
        db.update("INSERT INTO con3_data_scope(scope_id,task_id,organization_id,scope_version,kind) VALUES('scope','task','organization',1,'TASK_METADATA')");
        write(input.manifest()); write(input.records()); write(input.natureEvidence()); for (var raw : input.originals()) write(raw);
        String mapBytes = "TM-JSON-DIRECT-V1:1|sourceRecordId:string|required|registrationNumber:string-or-null|name:string-or-null"
                + "|applicants:ordered-strings|applicantAddress:string-or-null|classes:ordered-code-name-goodsServices-similarGroups"
                + "|registrationDate:YYYY-YYYY-MM-YYYY-MM-DD|validUntil:YYYY-YYYY-MM-YYYY-MM-DD"
                + "|sourceStatus:UNMAPPED|sourceUpdatedAt:string-or-null|TM-NORMALIZED-C14N-V1|TM-RELATIONS-C14N-V1";
        file("map.txt", mapBytes.getBytes(StandardCharsets.UTF_8)); file("rights.txt", "Isolated test evidence only".getBytes(StandardCharsets.UTF_8));
        for (String purpose : List.of("RETENTION", "BACKUP", "RECOVERY")) {
            ObjectNode policy = capture.json.createObjectNode().put("schema", "TM-PROJECTION-POLICY-V1").put("purpose", purpose)
                    .put("sourceId", "source").put("contractVersion", "contract-v1").put("organization", "organization").put("taskId", "task")
                    .put("until", ProductTrademarkProjectionFixture.UNTIL.toString()).put("sourceRef", "isolated-product-decision")
                    .put("version", "1").put("responsibleParty", "isolated-test").put("decidedAt", ProductTrademarkProjectionFixture.NOW.minusSeconds(60).toString());
            var objects = policy.putArray("projectionObjects"); ProductTrademarkProjectionApproval.OBJECTS.forEach(objects::add);
            file(purpose.toLowerCase(java.util.Locale.ROOT) + ".json", capture.json.writeValueAsBytes(policy));
        }
        approval.put("operation", "TRADEMARK_CAPTURE_PROJECT_V1").put("approvalRef", "isolated-approval").put("version", "1")
                .put("responsibleParty", "isolated-test").put("decidedAt", ProductTrademarkProjectionFixture.NOW.minusSeconds(60).toString())
                .put("subject", "maintainer").put("certificateSHA", certSHA).put("maintainerVersion", 1)
                .put("organization", "organization").put("taskId", "task").put("taskAuthorizationVersion", 7).put("scopeId", "scope")
                .put("sourceId", "source").put("contractVersion", "contract-v1").put("registeredSourceVersion", input.registeredSourceVersion())
                .put("verificationId", "verification").put("manifestSHA", verified.manifestSha256()).put("recordsSHA", verified.recordsSha256())
                .put("inventorySHA", verified.inventorySha256()).put("bindingSetSHA", verified.bindingSetSha256())
                .put("mapId", input.mapping().id()).put("mapVersion", input.mapping().version()).put("mapSHA", input.mapping().sha256()).put("mapEvidenceRef", "map.txt")
                .put("sourceNature", "RESTRICTED").put("natureEvidenceRef", input.natureEvidence().ref()).put("natureEvidenceSHA", input.natureEvidence().sha256())
                .put("rightsEvidenceRef", "rights.txt").put("rightsEvidenceSHA", hash("rights.txt"))
                .put("retentionRef", "retention.json").put("retentionVersion", 1).put("retentionEvidenceSHA", hash("retention.json"))
                .put("backupRef", "backup.json").put("backupEvidenceSHA", hash("backup.json"))
                .put("recoveryRef", "recovery.json").put("recoveryEvidenceSHA", hash("recovery.json"))
                .put("primaryDatabaseIdentitySHA", ProductTrademarkSourceProjectionAuthority.databaseIdentitySHA(db))
                .put("authorityDatabaseIdentitySHA", ProductTrademarkSourceProjectionAuthority.databaseIdentitySHA(authority))
                .put("notBefore", ProductTrademarkProjectionFixture.NOW.minusSeconds(30).toString());
        for (String key : List.of("validUntil", "retainUntil", "backupUntil", "recoveryUntil")) approval.put(key, ProductTrademarkProjectionFixture.UNTIL.toString());
        var objects = approval.putArray("projectionObjects"); ProductTrademarkProjectionApproval.OBJECTS.forEach(objects::add);
        seal();
        db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,retention_version,retention_evidence_sha,collected_at,valid_until,status,row_version,created_at) "
                + "VALUES('source','contract-v1',?,'{}',?,'rights.txt',?,'retention.json',1,?,?,?,'ACTIVE',2,?)", input.registeredSourceVersion(), "a".repeat(64), hash("rights.txt"), hash("retention.json"),
                ts(ProductTrademarkProjectionFixture.NOW.minusSeconds(60)), ts(ProductTrademarkProjectionFixture.UNTIL), ts(ProductTrademarkProjectionFixture.NOW));
        db.update("INSERT INTO con4_source_verification(verification_id,source_id,contract_version,source_version,candidate_row_version,manifest_ref,manifest_sha,records_sha,inventory_sha,record_count,raw_count,verifier_ref,verified_at) "
                + "VALUES('verification','source','contract-v1',?,1,?,?,?,?,21,21,'test',?)", input.registeredSourceVersion(), input.manifest().ref(), verified.manifestSha256(), verified.recordsSha256(), verified.inventorySha256(), ts(ProductTrademarkProjectionFixture.NOW));
        db.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,rights_ref,rights_sha,capability_ref,capability_sha,responsible_party,maintainer_ref,coverage_sha,valid_until,decided_at) "
                + "VALUES('decision','source','contract-v1','rights.txt',?,'capability.json',?,'isolated-test','maintainer',?,?,?)", hash("rights.txt"), pin.capabilitySHA(), "a".repeat(64), ts(ProductTrademarkProjectionFixture.UNTIL), ts(ProductTrademarkProjectionFixture.NOW));
        db.update("INSERT INTO con4_source_product_decision(decision_id,decision_version,decision_ref,decision_sha,rights_evidence_status,organization_id,task_id,scope_id,retention_ref,retention_version,retention_evidence_sha) "
                + "VALUES('decision','1','decision.json',?,'UNRESOLVED','organization','task','scope','retention.json',1,?)", "a".repeat(64), hash("retention.json"));
        for (String field : List.of("NUMBER", "NAME", "APPLICANTS", "ADDRESS", "CLASSES", "DATES", "STATUS", "SOURCE_REFERENCE")) {
            db.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,valid_until) VALUES('decision',?,'SAVE',?)", field, ts(ProductTrademarkProjectionFixture.UNTIL));
            if (!List.of("ADDRESS", "SOURCE_REFERENCE").contains(field)) db.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,valid_until) VALUES('decision',?,'INDEX',?)", field, ts(ProductTrademarkProjectionFixture.UNTIL));
        }
        db.update("INSERT INTO con4_source_current(source_id,contract_version,decision_id,source_epoch,status,row_version,history_sha,updated_at) "
                + "VALUES('source','contract-v1','decision',1,'ACTIVE',1,?,?)", "a".repeat(64), ts(ProductTrademarkProjectionFixture.NOW));
        refreshHistory();
        watermark = new ProductSourceWatermark(db, authority, new TransactionTemplate(new DataSourceTransactionManager(primary)));
        availability = new ProductSourceAvailability(db, watermark, capture.clock);
        maintenance = new ProductMaintenanceProof(authority, new TransactionTemplate(new DataSourceTransactionManager(independent)));
        files = new ProductSourceEvidenceFiles(root, 64 * 1024 * 1024);
        adapter = adapter(List.of(pin));
    }
    void refreshHistory() {
        String digest = ProductSourceHistoryBatch.digest(db, "source", true);
        db.update("UPDATE con4_source_current SET history_sha=? WHERE source_id='source'", digest);
        authority.update("DELETE FROM id4_source_watermark WHERE source_id='source'");
        authority.update("INSERT INTO id4_source_watermark(source_id,source_epoch,state,intent_id,history_sha) VALUES('source',1,'READY',NULL,?)", digest);
    }
    ProductTrademarkSourceProjectionAuthority adapter(List<ProductTrademarkProjectionApproval.Pin> pins) {
        return new ProductTrademarkSourceProjectionAuthority(db, authority, files, capture.json, capture.clock, pins);
    }
    void seal() throws Exception {
        ObjectNode scope = approval.deepCopy(); scope.remove(List.of("evidenceRef", "evidenceSHA", "approvedScopeSHA"));
        ObjectNode evidence = capture.json.createObjectNode().put("schema", "TM-PROJECTION-APPROVAL-V1"); evidence.set("scope", scope);
        file("approval.json", capture.json.writeValueAsBytes(evidence));
        approval.put("evidenceRef", "approval.json").put("evidenceSHA", hash("approval.json"));
        ObjectNode canonical = approval.deepCopy(); canonical.remove("approvedScopeSHA");
        approval.put("approvedScopeSHA", new ProductTrademarkCaptureJson(capture.json).canonicalSha(canonical));
        ObjectNode capability = capture.json.createObjectNode().put("schema", "TM-PROJECTION-CAPABILITY-V1").put("sourceNature", "RESTRICTED")
                .put("natureEvidenceRef", input.natureEvidence().ref()).put("natureEvidenceSHA", input.natureEvidence().sha256()); capability.set("projectionApproval", approval);
        file("capability.json", capture.json.writeValueAsBytes(capability));
        pin = new ProductTrademarkProjectionApproval.Pin("source", "contract-v1", "capability.json", hash("capability.json"), "approval.json", hash("approval.json"), approval.path("approvedScopeSHA").asText());
    }
    void write(ProductTrademarkCapture.Artifact artifact) throws Exception { file(artifact.ref(), artifact.bytes()); }
    void file(String ref, byte[] bytes) throws Exception { Path path = root.resolve(ref); Files.createDirectories(path.getParent()); Files.write(path, bytes); }
    String hash(String ref) throws Exception { return sha(Files.readAllBytes(root.resolve(ref))); }
    static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static JdbcDataSource database() { var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:tm-auth-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1"); source.setUser("sa"); return source; }
    private static void script(JdbcDataSource source, String name) throws Exception {
        Path repo = Path.of("").toAbsolutePath(); while (repo != null && !Files.exists(repo.resolve(name))) repo = repo.getParent();
        if (repo == null) throw new IllegalStateException("Migration unavailable");
        try (Connection connection = source.getConnection()) { ScriptUtils.executeSqlScript(connection, new FileSystemResource(repo.resolve(name))); }
    }
    private static X509Certificate certificate() throws Exception {
        // Real DER certificate; the request fixture does not claim an actual TLS handshake.
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        try (var stream = Files.newInputStream(Path.of(System.getProperty("java.home"), "lib", "security", "cacerts"))) { store.load(stream, "changeit".toCharArray()); }
        var aliases = java.util.Collections.list(store.aliases()); aliases.sort(String::compareTo);
        for (String alias : aliases) {
            var candidate = store.getCertificate(alias);
            if (candidate instanceof X509Certificate certificate) try {
                certificate.checkValidity(java.util.Date.from(ProductTrademarkProjectionFixture.NOW));
                if (certificate.getNotAfter().toInstant().isAfter(ProductTrademarkProjectionFixture.UNTIL)) return certificate;
            } catch (java.security.cert.CertificateException expired) { /* Try the next real trust-store certificate. */ }
        }
        throw new IllegalStateException("No valid certificate fixture available");
    }
}
