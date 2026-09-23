package cn.xinji.agentplatform.knowledgebase.adapter.out.dm8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public final class LocalKnowledgeBaseImporter {
    private static final String BASE = "/public-pilot/";
    private static final Map<String, SourceInfo> SOURCES = Map.of(
            "SURECHEMBL_PUBLIC_SEARCH", new SourceInfo("SureChEMBL", "https://www.surechembl.org/", "PUBLIC_WEB_API", "NON_CENTER_PUBLIC_SOURCE"),
            "ESPACENET_PUBLIC_PILOT", new SourceInfo("SureChEMBL", "https://www.surechembl.org/", "PUBLIC_WEB_API", "NON_CENTER_PUBLIC_SOURCE"),
            "EPO_LINKED_OPEN_DATA", new SourceInfo("EPO Linked Open Data", "https://data.epo.org/linked-data/", "PUBLIC_LINKED_DATA_NO_AUTH", "NON_CENTER_PUBLIC_SOURCE"),
            "USPTO_TMOG_DERIVED_SNAPSHOT", new SourceInfo("USPTO TMOG derived snapshot", "https://www.uspto.gov/", "PUBLIC_STATIC_DOWNLOAD", "NON_CENTER_PUBLIC_DERIVED_SOURCE"),
            "SPC_IP_COURT_SELECTED_JUDGMENTS", new SourceInfo("最高人民法院知识产权法庭精品裁判", "https://ipc.court.gov.cn/", "PUBLIC_WEB_PAGES", "PUBLIC_JUDICIAL_WEBSITE_NON_CENTER_SOURCE"));
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public LocalKnowledgeBaseImporter(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    public ImportResult importAll() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int patents = importDomain(connection, "PATENT", "public-patents-local-v1.json");
                int trademarks = importDomain(connection, "TRADEMARK", "public-trademarks-v1.json");
                int cases = importDomain(connection, "CASE", "public-cases-v1.json");
                connection.commit();
                return new ImportResult(patents, trademarks, cases);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Local knowledge-base import failed", failure);
        }
    }

    private int importDomain(Connection connection, String domain, String resource) throws Exception {
        JsonNode records = resource(resource);
        int imported = 0;
        for (JsonNode record : records) {
            String sourceId = text(record, "sourceId");
            String stableKey = text(record, "sourceRecordId");
            String sourceVersion = text(record, "sourceVersion");
            String datasetId = dataset(domain, record);
            String versionId = id("version", domain, sourceId, stableKey, sourceVersion);
            ensureSource(connection, sourceId);
            ensureBatch(connection, datasetId, sourceId, records.size());
            if (exists(connection, "SELECT 1 FROM kb_record_version WHERE record_version_id=?", versionId)) continue;
            String payload = mapper.writeValueAsString(record);
            String rawId = id("raw", datasetId, stableKey);
            insertRaw(connection, rawId, datasetId, stableKey, sourceRef(record), payload);
            insertVersion(connection, versionId, domain, stableKey, sourceId, sourceVersion, rawId, payload);
            insertProvenance(connection, versionId, sourceRef(record), record);
            switch (domain) {
                case "PATENT" -> insertPatent(connection, versionId, record);
                case "TRADEMARK" -> insertTrademark(connection, versionId, record);
                case "CASE" -> insertCase(connection, versionId, record);
                default -> throw new IllegalArgumentException("Unsupported domain " + domain);
            }
            imported++;
        }
        return imported;
    }

    private void ensureSource(Connection c, String sourceId) throws Exception {
        if (exists(c, "SELECT 1 FROM kb_source WHERE source_id=?", sourceId)) return;
        SourceInfo info = SOURCES.getOrDefault(sourceId, new SourceInfo(sourceId, "https://example.invalid/", "PUBLIC_SNAPSHOT", "NON_CENTER_PUBLIC_SOURCE"));
        execute(c, "INSERT INTO kb_source(source_id,source_name,provider_name,entry_url,jurisdiction,access_mode,authority_status,license_review_status,usage_decision,limitation_text,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                sourceId, info.name(), info.name(), info.url(), null, info.accessMode(), info.authorityStatus(), "REVIEW_REQUIRED",
                "INTERNAL_DEVELOPMENT_PILOT_ONLY", "公开可访问不等于生产授权；不得视为中心权威数据。", Timestamp.from(Instant.now()));
    }

    private void ensureBatch(Connection c, String datasetId, String sourceId, int count) throws Exception {
        String batchId = id("batch", datasetId);
        if (exists(c, "SELECT 1 FROM kb_ingest_batch WHERE batch_id=?", batchId)) return;
        execute(c, "INSERT INTO kb_ingest_batch(batch_id,dataset_id,source_id,collector_version,started_at,completed_at,query_scope,record_count,records_sha256,status,limitation_text) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                batchId, datasetId, sourceId, "LOCAL-KB-IMPORT-V1", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                "仓库内版本化公开快照", count, id("resource", datasetId), "COMPLETED", "仅供内部开发；来源许可仍需复核。" );
    }

    private void insertRaw(Connection c, String rawId, String datasetId, String stableKey, String sourceRef, String payload) throws Exception {
        if (exists(c, "SELECT 1 FROM kb_raw_record WHERE raw_record_id=?", rawId)) return;
        execute(c, "INSERT INTO kb_raw_record(raw_record_id,batch_id,source_record_id,request_url,content_type,content_path,raw_content,content_sha256,captured_at,response_metadata) VALUES(?,?,?,?,?,?,?,?,?,?)",
                rawId, id("batch", datasetId), stableKey, sourceRef, "application/json", "data snapshot: " + datasetId,
                payload, sha(payload), Timestamp.from(Instant.now()), "{\"preserved\":true}");
    }

    private void insertVersion(Connection c, String id, String domain, String stableKey, String sourceId,
                               String sourceVersion, String rawId, String payload) throws Exception {
        execute(c, "INSERT INTO kb_record_version(record_version_id,domain_code,stable_record_key,source_id,source_version,raw_record_id,normalized_payload,normalized_sha256,data_mode,effective_at,imported_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, domain, stableKey, sourceId, sourceVersion, rawId, payload, sha(payload), "PUBLIC_LOCAL", null, Timestamp.from(Instant.now()));
    }

    private void insertProvenance(Connection c, String versionId, String locator, JsonNode record) throws Exception {
        List<String> missing = record.has("missingFields") ? mapper.convertValue(record.get("missingFields"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)) : List.of();
        Iterator<Map.Entry<String, JsonNode>> fields = record.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String state = value == null || value.isNull() || value.isMissingNode() || (value.isArray() && value.isEmpty())
                    ? (missing.contains(field.getKey()) ? "SOURCE_NOT_PROVIDED" : "MISSING") : "PRESENT";
            String serialized = value == null ? "null" : value.toString();
            execute(c, "INSERT INTO kb_field_provenance(provenance_id,record_version_id,field_path,value_state,value_sha256,source_locator,extraction_method,confidence_value,review_status,derived_flag,method_version) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    id("field", versionId, field.getKey()), versionId, "/" + field.getKey(), state, sha(serialized), locator,
                    "SNAPSHOT_MAPPING", null, "UNREVIEWED", "N", "LOCAL-KB-IMPORT-V1");
        }
    }

    private void insertPatent(Connection c, String versionId, JsonNode record) throws Exception {
        execute(c, "INSERT INTO kb_patent(record_version_id,application_number,publication_number,grant_publication_number,patent_type,application_date,publication_date,grant_date,source_legal_status,source_ref) VALUES(?,?,?,?,?,?,?,?,?,?)",
                versionId, nullable(record, "applicationNumber"), text(record, "publicationNumber"), null, null,
                date(record, "applicationDate"), date(record, "publicationDate"), null, null, sourceRef(record));
        execute(c, "INSERT INTO kb_patent_title(record_version_id,title_order,language_code,title_text,derived_flag) VALUES(?,?,?,?,?)",
                versionId, 1, "und", text(record, "title"), "N");
        insertPatentParties(c, versionId, record, "applicants", "APPLICANT");
        insertPatentParties(c, versionId, record, "inventors", "INVENTOR");
        insertPatentClasses(c, versionId, record, "ipc", "IPC");
        insertPatentClasses(c, versionId, record, "cpc", "CPC");
        insertPatentDocument(c, versionId, "ABSTRACT", record, "abstract", "abstractState");
        insertPatentDocument(c, versionId, "CLAIMS", record, "claims", "claimsState");
    }

    private void insertPatentParties(Connection c, String versionId, JsonNode record, String field, String type) throws Exception {
        int order = 0; for (JsonNode value : record.path(field)) execute(c,
                "INSERT INTO kb_patent_party(record_version_id,party_type,party_order,party_name,address_text) VALUES(?,?,?,?,?)",
                versionId, type, ++order, value.asText(), null);
    }
    private void insertPatentClasses(Connection c, String versionId, JsonNode record, String field, String scheme) throws Exception {
        int order = 0; for (JsonNode value : record.path(field)) execute(c,
                "INSERT INTO kb_patent_classification(record_version_id,scheme_code,class_order,classification_code,scheme_version) VALUES(?,?,?,?,?)",
                versionId, scheme, ++order, value.asText(), null);
    }
    private void insertPatentDocument(Connection c, String versionId, String type, JsonNode record, String field, String stateField) throws Exception {
        String value = nullable(record, field); String state = nullable(record, stateField);
        execute(c, "INSERT INTO kb_patent_document(document_id,record_version_id,document_type,language_code,content_state,document_text,external_ref,content_sha256,derived_flag) VALUES(?,?,?,?,?,?,?,?,?)",
                id("doc", versionId, type), versionId, type, null, state == null ? "SOURCE_NOT_PROVIDED" : state,
                value, sourceRef(record), value == null ? null : sha(value), "N");
    }

    private void insertTrademark(Connection c, String versionId, JsonNode record) throws Exception {
        execute(c, "INSERT INTO kb_trademark(record_version_id,application_number,registration_number,mark_name,jurisdiction,filing_date,registration_date,valid_until,source_status,source_ref) VALUES(?,?,?,?,?,?,?,?,?,?)",
                versionId, nullable(record, "serialNumber"), nullable(record, "registrationNumber"), text(record, "name"), "US",
                date(record, "filingDate"), date(record, "registrationDate"), date(record, "validUntil"), nullable(record, "sourceStatusCode"), sourceRef(record));
        int order = 0; for (JsonNode value : record.path("classes")) execute(c,
                "INSERT INTO kb_trademark_class(record_version_id,class_order,class_code,class_name,goods_services,similar_groups) VALUES(?,?,?,?,?,?)",
                versionId, ++order, text(value, "code"), nullable(value, "name"), value.path("goodsServices").toString(), value.path("similarGroups").toString());
        if (record.hasNonNull("imageRef")) execute(c,
                "INSERT INTO kb_trademark_image(image_id,record_version_id,image_ref,media_type,content_sha256,content_state) VALUES(?,?,?,?,?,?)",
                id("image", versionId), versionId, text(record, "imageRef"), null, null, "EXTERNAL_REFERENCE");
        if (record.hasNonNull("gazetteEvent")) execute(c,
                "INSERT INTO kb_trademark_event(event_id,record_version_id,event_type,business_date,status_text,result_text,source_ref) VALUES(?,?,?,?,?,?,?)",
                id("event", versionId, "gazette"), versionId, "GAZETTE_EVENT", date(record, "publicationDate"), text(record, "gazetteEvent"), null, sourceRef(record));
    }

    private void insertCase(Connection c, String versionId, JsonNode record) throws Exception {
        execute(c, "INSERT INTO kb_case(record_version_id,case_number,docket_number,title,case_type,ip_type,institution,procedure_code,hearing_date,decision_date,outcome_excerpt,source_ref) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                versionId, nullable(record, "caseNumber"), nullable(record, "docketNumber"), text(record, "title"), nullable(record, "caseType"),
                nullable(record, "intellectualPropertyType"), nullable(record, "institution"), nullable(record, "procedure"),
                date(record, "hearingDate"), date(record, "decisionDate"), nullable(record, "outcomeExcerpt"), sourceRef(record));
        int order = 0; for (JsonNode document : record.path("documents")) {
            String body = nullable(document, "text");
            execute(c, "INSERT INTO kb_case_document(document_id,record_version_id,source_document_version,content_state,ocr_state,document_text,original_ref,source_locator,content_sha256) VALUES(?,?,?,?,?,?,?,?,?)",
                    id("case-doc", versionId, String.valueOf(++order)), versionId, text(document, "versionId"), text(document, "state"),
                    nullable(document, "ocrState"), body, nullable(document, "originalRef"), nullable(document, "sourceLocator"), body == null ? null : sha(body));
        }
    }

    private JsonNode resource(String name) throws Exception {
        try (InputStream input = LocalKnowledgeBaseImporter.class.getResourceAsStream(BASE + name)) {
            if (input == null) throw new IllegalStateException("Missing resource " + name);
            return mapper.readTree(input);
        }
    }
    private static String dataset(String domain, JsonNode record) {
        return switch (domain) {
            case "PATENT" -> text(record, "sourceDatasetId");
            case "TRADEMARK" -> "CON-01-TRADEMARK-PUBLIC-PILOT-V1";
            case "CASE" -> "CON-03-CASE-PUBLIC-PILOT-V1";
            default -> throw new IllegalArgumentException(domain);
        };
    }
    private static String sourceRef(JsonNode node) { return node.hasNonNull("sourceUrl") ? text(node, "sourceUrl") : text(node, "sourceRef"); }
    private static String text(JsonNode node, String name) { String value = nullable(node, name); if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
    private static String nullable(JsonNode node, String name) { JsonNode value = node.get(name); return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText(); }
    private static Date date(JsonNode node, String name) { String value = nullable(node, name); return value == null ? null : Date.valueOf(LocalDate.parse(value)); }
    private static boolean exists(Connection c, String sql, String key) throws Exception { try (PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, key); try (ResultSet r = s.executeQuery()) { return r.next(); } } }
    private static void execute(Connection c, String sql, Object... values) throws Exception { try (PreparedStatement s = c.prepareStatement(sql)) { for (int i=0;i<values.length;i++) s.setObject(i+1, values[i]); s.executeUpdate(); } }
    private static String id(String... values) { return sha(String.join("|", values)).substring(0, 48); }
    private static String sha(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record SourceInfo(String name, String url, String accessMode, String authorityStatus) {}
    public record ImportResult(int patentsImported, int trademarksImported, int casesImported) {}
}
