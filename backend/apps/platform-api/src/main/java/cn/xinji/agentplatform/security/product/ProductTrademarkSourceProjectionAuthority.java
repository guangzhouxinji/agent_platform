package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.require;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only current certification. A certificate is not a locked write lease. */
final class ProductTrademarkSourceProjectionAuthority {
    private record Maintainer(String subject, String organization, String certificate, String roles,
                              String status, long version, Instant certificateUntil) { }
    private record Database(String server, String schema, String user) {
        String hash() {
            var json = new ProductTrademarkCaptureJson(new ObjectMapper());
            return json.canonicalSha(json.object().put("server", server).put("schema", schema).put("user", user));
        }
    }
    private record Facts(Map<String, String> values, Instant taskUntil, Instant sourceUntil) {
        Facts { values = Map.copyOf(values); }
        String value(String key) { return values.get(key); }
    }
    private record Certified(Maintainer maintainer, Facts facts, ProductSourceAvailability.Active source,
                             String approvalHash, String bindingHash, List<Instant> deadlines, Instant expiresAt) {
        Certified { deadlines = List.copyOf(deadlines); }
    }
    static final class AuthenticatedScope {
        private final ProductTrademarkSourceProjectionAuthority owner;
        private final Thread thread = Thread.currentThread();
        private final ProductTrademarkProjectionApproval.Pin pin;
        private final Certified facts;
        private final ProductTrademarkProjectionEvidence.Loaded evidence;
        private AuthenticatedScope(ProductTrademarkSourceProjectionAuthority owner,
                ProductTrademarkProjectionApproval.Pin pin, Certified facts,
                ProductTrademarkProjectionEvidence.Loaded evidence) {
            this.owner = owner; this.pin = pin; this.facts = facts; this.evidence = evidence;
        }
        Instant expiresAt() { return facts.expiresAt(); }
        ProductTrademarkCapture.Input originals() { return evidence.input(); }
        String sourceId() { return pin.sourceId(); }
    }
    private final JdbcTemplate db, authority;
    private final ProductMaintenanceProof maintenance;
    private final ProductSourceWatermark watermark;
    private final ProductSourceAvailability availability;
    private final ProductSourceEvidenceFiles files;
    private final ProductTrademarkProjectionEvidence evidence;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final List<ProductTrademarkProjectionApproval.Pin> pins;

    ProductTrademarkSourceProjectionAuthority(JdbcTemplate db, JdbcTemplate authority,
            ProductSourceEvidenceFiles files,
            ObjectMapper mapper, Clock clock, List<ProductTrademarkProjectionApproval.Pin> pins) {
        this.db = Objects.requireNonNull(db); this.authority = Objects.requireNonNull(authority);
        this.files = Objects.requireNonNull(files);
        this.mapper = Objects.requireNonNull(mapper); this.clock = Objects.requireNonNull(clock);
        this.pins = List.copyOf(pins); require(this.pins.size() <= 8);
        var unique = new java.util.HashSet<String>();
        for (var pin : pins) require(unique.add(pin.sourceId() + "\n" + pin.contractVersion()));
        require(db.getDataSource() != authority.getDataSource() && !database(db).equals(database(authority)));
        this.maintenance = new ProductMaintenanceProof(authority,
                new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(authority.getDataSource()))));
        this.watermark = new ProductSourceWatermark(db, authority,
                new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()))));
        this.availability = new ProductSourceAvailability(db, this.watermark, clock);
        evidence = new ProductTrademarkProjectionEvidence(files, mapper);
    }
    AuthenticatedScope authenticate(HttpServletRequest request, String sourceId, String contractVersion) {
        standalone();
        identity(sourceId); identity(contractVersion);
        var matches = pins.stream().filter(pin -> pin.sourceId().equals(sourceId) && pin.contractVersion().equals(contractVersion)).toList();
        require(matches.size() == 1); return certify(request, matches.getFirst());
    }
    void recheck(HttpServletRequest request, AuthenticatedScope scope) {
        standalone();
        require(scope != null && scope.owner == this && scope.thread == Thread.currentThread());
        require(clock.instant().isBefore(scope.facts.expiresAt()));
        AuthenticatedScope current = certify(request, scope.pin);
        require(scope.facts.equals(current.facts));
    }
    private AuthenticatedScope certify(HttpServletRequest request, ProductTrademarkProjectionApproval.Pin pin) {
        Maintainer before = maintainer(request);
        String operator = maintenance.require(request, ProductMaintenanceProof.Role.SOURCES);
        require(operator.equals(before.subject() + "#" + before.certificate()));
        var approved = new ProductTrademarkProjectionApproval(mapper, files, pin); approved.current(clock.instant());
        match(approved, "subject", before.subject()); match(approved, "organization", before.organization());
        match(approved, "certificateSHA", before.certificate()); require(approved.number("maintainerVersion") == before.version());
        Database mainDatabase = database(db), independentDatabase = database(authority);
        require(db.getDataSource() != authority.getDataSource() && !mainDatabase.equals(independentDatabase));
        match(approved, "primaryDatabaseIdentitySHA", mainDatabase.hash());
        match(approved, "authorityDatabaseIdentitySHA", independentDatabase.hash());
        var current = watermark.current(pin.sourceId()); require("ACTIVE".equals(current.status()));
        Facts facts = facts(pin.sourceId(), pin.contractVersion());
        for (String key : List.of("organization", "taskId", "scopeId", "verificationId", "registeredSourceVersion",
                "rightsEvidenceRef", "rightsEvidenceSHA", "retentionRef", "retentionEvidenceSHA", "responsibleParty"))
            match(approved, key, facts.value(key));
        require(approved.number("taskAuthorizationVersion") == Long.parseLong(facts.value("taskAuthorizationVersion"))
                && approved.number("retentionVersion") == Long.parseLong(facts.value("retentionVersion")));
        require(pin.capabilityRef().equals(facts.value("capabilityRef")) && pin.capabilitySHA().equals(facts.value("capabilitySHA")));
        var loaded = evidence.load(approved, facts.value("manifestRef"), facts.value("manifestSHA"), facts.value("recordsSHA"),
                facts.value("inventorySHA"), Long.parseLong(facts.value("recordCount")), Long.parseLong(facts.value("rawCount")));
        var save = new java.util.TreeSet<>(Set.of("NUMBER", "NAME", "APPLICANTS", "ADDRESS", "CLASSES", "DATES", "STATUS", "SOURCE_REFERENCE"));
        save.addAll(loaded.extraSaveFields());
        var index = Set.of("NUMBER", "NAME", "APPLICANTS", "CLASSES", "DATES", "STATUS");
        var active = availability.requireFields(pin.sourceId(), save, "SAVE");
        require(active.equals(availability.requireFields(pin.sourceId(), index, "INDEX")));
        require(active.contractVersion().equals(pin.contractVersion()) && active.sourceVersion().equals(facts.value("registeredSourceVersion"))
                && active.decisionId().equals(current.decisionId()) && active.epoch() == current.epoch() && active.rowVersion() == current.rowVersion());
        List<Instant> deadlines = new ArrayList<>(List.of(approved.until("validUntil"), approved.until("retainUntil"),
                approved.until("recoveryUntil"), facts.taskUntil(), facts.sourceUntil(), before.certificateUntil()));
        for (String field : save) deadlines.add(availability.useUntil(pin.sourceId(), field, "SAVE"));
        for (String field : new java.util.TreeSet<>(index)) deadlines.add(availability.useUntil(pin.sourceId(), field, "INDEX"));
        Instant expires = deadlines.stream().min(Instant::compareTo).orElseThrow();
        require(before.equals(maintainer(request)) && facts.equals(facts(pin.sourceId(), pin.contractVersion()))
                && current.equals(watermark.current(pin.sourceId())) && active.equals(availability.requireFields(pin.sourceId(), save, "SAVE"))
                && active.equals(availability.requireFields(pin.sourceId(), index, "INDEX")));
        var finalApproved = new ProductTrademarkProjectionApproval(mapper, files, pin);
        require(approved.fingerprint().equals(finalApproved.fingerprint())
                && mainDatabase.equals(database(db)) && independentDatabase.equals(database(authority)));
        var finalLoaded = evidence.load(finalApproved, facts.value("manifestRef"), facts.value("manifestSHA"), facts.value("recordsSHA"),
                facts.value("inventorySHA"), Long.parseLong(facts.value("recordCount")), Long.parseLong(facts.value("rawCount")));
        require(loaded.capture().bindingSetSha256().equals(finalLoaded.capture().bindingSetSha256())
                && loaded.extraSaveFields().equals(finalLoaded.extraSaveFields()));
        int deadlineIndex = 6;
        for (String field : save) require(deadlines.get(deadlineIndex++).equals(availability.useUntil(pin.sourceId(), field, "SAVE")));
        for (String field : new java.util.TreeSet<>(index)) require(deadlines.get(deadlineIndex++).equals(availability.useUntil(pin.sourceId(), field, "INDEX")));
        require(before.equals(maintainer(request)) && facts.equals(facts(pin.sourceId(), pin.contractVersion()))
                && current.equals(watermark.current(pin.sourceId()))
                && mainDatabase.equals(database(db)) && independentDatabase.equals(database(authority)));
        approved.current(clock.instant()); require(clock.instant().isBefore(expires));
        return new AuthenticatedScope(this, pin, new Certified(before, facts, active, approved.fingerprint(),
                loaded.capture().bindingSetSha256(), deadlines, expires), loaded);
    }
    private Maintainer maintainer(HttpServletRequest request) {
        require(request != null && request.isSecure());
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        require(attribute instanceof X509Certificate[] && ((X509Certificate[]) attribute).length > 0);
        X509Certificate certificate = ((X509Certificate[]) attribute)[0]; require(certificate != null);
        try {
            certificate.checkValidity(java.util.Date.from(clock.instant()));
            String fingerprint = sha(certificate.getEncoded());
            var rows = authority.query("SELECT subject_ref,organization,certificate_sha,allowed_roles,status,version "
                    + "FROM id2_maintainer WHERE singleton_key=1", (rs, row) -> new Maintainer(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), certificate.getNotAfter().toInstant()));
            require(rows.size() == 1); Maintainer result = rows.getFirst();
            require("ACTIVE".equals(result.status()) && result.version() > 0 && fingerprint.equals(result.certificate())
                    && List.of(result.roles().split(",")).contains("SOURCES") && clock.instant().isBefore(result.certificateUntil()));
            return result;
        } catch (java.security.cert.CertificateException invalid) { throw new IllegalStateException("Trademark maintenance certificate unavailable", invalid); }
    }
    private Facts facts(String sourceId, String contractVersion) {
        var rows = db.query("SELECT d.organization_id,d.task_id,d.scope_id,v.verification_id,k.source_version,a.rights_ref,a.rights_sha,"
                + "d.retention_ref,d.retention_version,d.retention_evidence_sha,a.responsible_party,t.authorization_version,"
                + "a.capability_ref,a.capability_sha,v.manifest_ref,v.manifest_sha,v.records_sha,v.inventory_sha,v.record_count,v.raw_count,"
                + "t.expires_at,k.valid_until,a.valid_until,o.version,s.scope_version,t.state,t.cancel_epoch "
                + "FROM con4_source_current c JOIN con4_source_contract k ON k.source_id=c.source_id AND k.contract_version=c.contract_version "
                + "JOIN con4_source_admission a ON a.decision_id=c.decision_id AND a.source_id=c.source_id AND a.contract_version=c.contract_version "
                + "JOIN con4_source_product_decision d ON d.decision_id=a.decision_id "
                + "JOIN con4_source_verification v ON v.source_id=c.source_id AND v.contract_version=c.contract_version AND v.source_version=k.source_version "
                + "JOIN con3_task t ON t.task_id=d.task_id AND t.organization_id=d.organization_id "
                + "JOIN con3_organization o ON o.organization_id=t.organization_id "
                + "JOIN con3_data_scope s ON s.scope_id=d.scope_id AND s.task_id=t.task_id AND s.organization_id=t.organization_id "
                + "WHERE c.source_id=? AND c.contract_version=? AND c.status='ACTIVE' AND k.status='ACTIVE' AND k.row_version=2 "
                + "AND v.candidate_row_version=1 AND d.rights_evidence_status<>'WITHDRAWN' AND o.status='ACTIVE' AND s.kind='TASK_METADATA'",
                (rs, row) -> {
                    var values = new java.util.LinkedHashMap<String, String>();
                    var keys = List.of("organization", "taskId", "scopeId", "verificationId", "registeredSourceVersion", "rightsEvidenceRef", "rightsEvidenceSHA",
                            "retentionRef", "retentionVersion", "retentionEvidenceSHA", "responsibleParty", "taskAuthorizationVersion", "capabilityRef", "capabilitySHA",
                            "manifestRef", "manifestSHA", "recordsSHA", "inventorySHA", "recordCount", "rawCount");
                    for (int i = 0; i < keys.size(); i++) values.put(keys.get(i), rs.getString(i + 1));
                    for (int i = 24; i <= 27; i++) values.put("native" + i, rs.getString(i));
                    Instant until = rs.getTimestamp(22).toInstant(), admission = rs.getTimestamp(23).toInstant();
                    if (admission.isBefore(until)) until = admission;
                    return new Facts(values, rs.getTimestamp(21).toInstant(), until);
                }, sourceId, contractVersion);
        require(rows.size() == 1); Facts result = rows.getFirst();
        require(!"CANCELLED".equals(result.value("native26")) && clock.instant().isBefore(result.taskUntil()) && clock.instant().isBefore(result.sourceUntil()));
        return result;
    }
    private static void match(ProductTrademarkProjectionApproval approved, String key, String actual) { require(approved.value(key).equals(actual)); }
    private static void standalone() {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.getResourceMap().isEmpty());
    }
    static String databaseIdentitySHA(JdbcTemplate db) { return database(db).hash(); }
    private static Database database(JdbcTemplate db) {
        try (Connection connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
            String url = connection.getMetaData().getURL(), schema = connection.getSchema(), user = connection.getMetaData().getUserName();
            require(url != null && schema != null && user != null && !schema.isBlank() && !user.isBlank());
            String server = url.split("[;?]", 2)[0];
            require(!server.matches("(?i).*?(password|pwd|user)=.*") && !server.contains("@"));
            return new Database(server, schema, user);
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Trademark database identity unavailable"); }
    }
}
