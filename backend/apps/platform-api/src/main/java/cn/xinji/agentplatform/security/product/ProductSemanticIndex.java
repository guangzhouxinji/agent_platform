package cn.xinji.agentplatform.security.product;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
/** Builds and reads an immutable, model-versioned semantic index for admitted source fields. */
final class ProductSemanticIndex {
    static final class Unavailable extends IllegalStateException {
        Unavailable(String reason) { super(reason); }
        Unavailable(String reason, Throwable cause) { super(reason, cause); }
    }
    record Receipt(String buildId, String sourceId, String field, String modelVersion,
                   int documentCount, int embeddedCount, int reusedCount, String buildSha) { }
    record Match(String sourceId, String documentId, String documentVersion,
                 String sourceVersion, String rawSha, String locatorRef, double semanticScore) { }
    private record Stored(String sourceVersion, String rawSha, String locatorRef,
                          String vectorSha, byte[] vector) { }
    private record Pending(PatentLexicalQuery.Document document, byte[] vector, String vectorSha) { }
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductSourceAvailability availability;
    private final ProductSourceWatermark watermark;
    private final ProductCollectedPatentTitles titles;
    private final PatentEmbeddingProvider embeddings;
    private final Clock clock;
    ProductSemanticIndex(JdbcTemplate db, TransactionTemplate transactions,
            ProductSourceAvailability availability, ProductSourceWatermark watermark,
            ProductCollectedPatentTitles titles, PatentEmbeddingProvider embeddings, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.availability = availability;
        this.watermark = watermark;
        this.titles = titles;
        this.embeddings = embeddings;
        this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_embedding_model", Integer.class);
    }
    PatentEmbeddingProvider.Model model() { return embeddings.model(); }
    Receipt build(String sourceId, String requestId, String actor) {
        return buildField(sourceId,"TITLE",requestId,actor);
    }
    Receipt buildField(String sourceId, String field, String requestId, String actor) {
        field(field);
        require(sourceId, requestId, actor);
        var active = availability.require(sourceId, field, "QUERY");
        watermark.assertUnchanged(sourceId, active.epoch());
        String modelVersion = modelVersion(field);
        String requestSha = ProductPatentSearchEvidenceCodec.digestValues(sourceId,
                active.contractVersion(), active.decisionId(), Long.toString(active.epoch()),
                field, modelVersion);
        List<Receipt> prior = prior(requestId, requestSha);
        if (!prior.isEmpty()) return prior.getFirst();
        List<PatentLexicalQuery.Document> documents = documents(active,field);
        if (documents.isEmpty()) throw new IllegalStateException("Source has no content for the selected field");
        List<Pending> pending = new ArrayList<>();
        int reused = 0;
        for (var document : documents) {
            List<Stored> stored = stored(document, modelVersion,field);
            if (!stored.isEmpty()) {
                if (stored.size() != 1 || !sameEvidence(stored.getFirst(), document))
                    throw new IllegalStateException("Existing semantic vector conflicts with source bytes");
                reused++;
                continue;
            }
            List<Stored> reusable = reusable(document, modelVersion,field);
            if (!reusable.isEmpty()) {
                if (reusable.size() != 1)
                    throw new IllegalStateException("Existing semantic vector conflicts with source bytes");
                String vectorSha = reusable.getFirst().vectorSha();
                if (!reusable.getFirst().rawSha().equals(document.rawSha())) {
                    float[] candidate = embedding(field,document.fields().get(field));
                    byte[] candidateBytes = ProductVectorCodec.encode(candidate, embeddings.model().dimension());
                    if (!ProductVectorCodec.sha(candidateBytes).equals(vectorSha))
                        throw new IllegalStateException("Existing semantic vector conflicts with source bytes");
                }
                pending.add(new Pending(document, null, vectorSha));
                reused++;
                continue;
            }
            float[] vector = embedding(field,document.fields().get(field));
            byte[] bytes = ProductVectorCodec.encode(vector, embeddings.model().dimension());
            pending.add(new Pending(document, bytes, ProductVectorCodec.sha(bytes)));
        }
        watermark.assertUnchanged(sourceId, active.epoch());
        int reusedCount = reused;
        return transactions.execute(status -> persist(active, field,requestId, requestSha, actor,
                modelVersion, documents.size(), reusedCount, pending));
    }

    void validate(ProductSourceAvailability.Active expected) {
        validateField(expected,"TITLE");
    }

    void validateField(ProductSourceAvailability.Active expected, String field) {
        field(field);
        var current = availability.require(expected.sourceId(), field, "QUERY");
        if (!current.equals(expected))
            throw new IllegalStateException("Current semantic source snapshot changed");
        watermark.assertUnchanged(current.sourceId(), current.epoch());
        String version = modelVersion(field);
        var material = documents(current,field);
        var vectors = ProductSemanticVectors.loadField(db,current.sourceId(),current.sourceVersion(),material,
                version,embeddings.model().dimension(),field);
        requireBuild(current,field,version,material,vectors);
        watermark.assertUnchanged(current.sourceId(), current.epoch());
    }

    List<Match> search(String sourceId, String query, int limit) {
        return searchField(sourceId,"TITLE",query,limit);
    }

    List<Match> searchField(String sourceId, String field, String query, int limit) {
        field(field);
        if (query == null || query.isBlank() || limit < 1) throw new IllegalArgumentException("Query and limit required");
        var active = availability.require(sourceId, field, "QUERY");
        List<PatentLexicalQuery.Document> documents = documents(active,field);
        String modelVersion = modelVersion(field);
        var vectors=ProductSemanticVectors.loadField(db,active.sourceId(),active.sourceVersion(),documents,
                modelVersion,embeddings.model().dimension(),field);
        requireBuild(active,field,modelVersion,documents,vectors);
        float[] queryVector = embedding(field,query);
        List<Match> matches = new ArrayList<>();
        for (var document : documents) {
            float[] vector = vectors.get(document.documentId());
            matches.add(new Match(document.sourceId(), document.documentId(), document.documentVersion(),
                    document.sourceVersion(), document.rawSha(), document.locatorRef(), cosine(queryVector,
                            vector)));
        }
        watermark.assertUnchanged(sourceId, active.epoch());
        return matches.stream().sorted(Comparator.comparingDouble(Match::semanticScore).reversed()
                        .thenComparing(Match::sourceId).thenComparing(Match::documentId)
                        .thenComparing(Match::documentVersion)).limit(limit).toList();
    }

    /** Reused bytes do not imply a build under the current admission tuple. No automatic rebuilding. */
    private void requireBuild(ProductSourceAvailability.Active active, String field, String version,
            List<PatentLexicalQuery.Document> documents, java.util.Map<String,float[]> vectors) {
        record Build(String sha, int count) { }
        var builds = db.query("SELECT DISTINCT build_sha,document_count FROM con4_vector_build "
                        + "WHERE source_id=? AND contract_version=? AND decision_id=? AND source_epoch=? "
                        + "AND field_name=? AND model_version=? AND completed_at<=?",
                (rs,row) -> new Build(rs.getString(1),rs.getInt(2)), active.sourceId(),
                active.contractVersion(),active.decisionId(),active.epoch(),field,version,
                Timestamp.from(clock.instant()));
        if (documents.isEmpty() || builds.size()!=1 || builds.getFirst().count()!=documents.size())
            throw new Unavailable("Semantic build receipt is missing or inconsistent for current admission");
        String sha = ProductPatentSearchEvidenceCodec.digestValues(documents.stream()
                .map(doc -> ProductVectorCodec.sha(ProductVectorCodec.encode(
                        vectors.get(doc.documentId()),embeddings.model().dimension())))
                .toArray(String[]::new));
        if (!sha.equals(builds.getFirst().sha()))
            throw new Unavailable("Semantic build receipt digest differs from current vector snapshot");
    }

    private Receipt persist(ProductSourceAvailability.Active active, String field,String requestId, String requestSha,
            String actor, String modelVersion, int documentCount, int reused, List<Pending> pending) {
        watermark.assertUnchanged(active.sourceId(), active.epoch());
        registerModel(modelVersion);
        for (Pending item : pending) {
            var document = item.document();
            Timestamp now = Timestamp.from(clock.instant());
            if (item.vector() != null)
                db.update("INSERT INTO con4_document_vector(source_id,document_id,document_version,field_name,"
                                + "model_version,source_version,raw_sha,locator_ref,vector_sha,vector_data,created_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", document.sourceId(), document.documentId(),
                        document.documentVersion(), field,modelVersion, document.sourceVersion(), document.rawSha(),
                        document.locatorRef(), item.vectorSha(), item.vector(), now);
            db.update("INSERT INTO con4_document_vector_binding(source_id,document_id,document_version,field_name,"
                            + "model_version,source_version,raw_sha,locator_ref,vector_sha,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)", document.sourceId(), document.documentId(),
                    document.documentVersion(), field,modelVersion, document.sourceVersion(), document.rawSha(),
                    document.locatorRef(), item.vectorSha(), now);
        }
        List<String> vectorShas = documents(active,field).stream().map(document -> {
            List<Stored> stored = stored(document, modelVersion,field);
            if (stored.size() != 1 || !sameEvidence(stored.getFirst(), document))
                throw new IllegalStateException("Semantic index cardinality changed");
            return stored.getFirst().vectorSha();
        }).toList();
        String buildSha = ProductPatentSearchEvidenceCodec.digestValues(vectorShas.toArray(String[]::new));
        String buildId = UUID.randomUUID().toString();
        int embedded = (int) pending.stream().filter(item -> item.vector() != null).count();
        db.update("INSERT INTO con4_vector_build(build_id,request_id,request_sha,source_id,contract_version,"
                        + "decision_id,source_epoch,field_name,model_version,document_count,embedded_count,"
                        + "reused_count,build_sha,actor_ref,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                buildId, requestId, requestSha, active.sourceId(), active.contractVersion(), active.decisionId(),
                active.epoch(), field,modelVersion, documentCount, embedded, reused, buildSha, actor,
                Timestamp.from(clock.instant()));
        return new Receipt(buildId, active.sourceId(), field, modelVersion, documentCount,
                embedded, reused, buildSha);
    }

    private void registerModel(String version) {
        var model = embeddings.model();
        List<String> rows = db.query("SELECT model_sha || ':' || tokenizer_sha FROM con4_embedding_model "
                + "WHERE model_version=?", (rs, row) -> rs.getString(1), version);
        String expected = model.modelSha256() + ":" + model.tokenizerSha256();
        if (!rows.isEmpty()) {
            if (rows.size() != 1 || !rows.getFirst().equalsIgnoreCase(expected))
                throw new IllegalStateException("Embedding model version conflicts with registered artifacts");
            return;
        }
        db.update("INSERT INTO con4_embedding_model VALUES(?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?)", version,
                model.provider(), model.modelId(), model.modelRevision(), model.modelSha256(),
                model.tokenizerSha256(), model.dimension(), model.maxTokens(), model.normalization(),
                model.licenseId(), Timestamp.from(clock.instant()));
    }

    private List<Stored> stored(PatentLexicalQuery.Document document, String modelVersion,String field) {
        return db.query("SELECT b.source_version,b.raw_sha,b.locator_ref,b.vector_sha,v.vector_data "
                        + "FROM con4_document_vector_binding b JOIN con4_document_vector v "
                        + "ON v.source_id=b.source_id AND v.document_id=b.document_id "
                        + "AND v.document_version=b.document_version AND v.field_name=b.field_name "
                        + "AND v.model_version=b.model_version AND v.vector_sha=b.vector_sha WHERE b.source_id=? "
                        + "AND b.document_id=? AND b.document_version=? AND b.field_name=? "
                        + "AND b.model_version=? AND b.source_version=?",
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBytes(5)), document.sourceId(),
                document.documentId(), document.documentVersion(), field,modelVersion, document.sourceVersion());
    }

    private List<Stored> reusable(PatentLexicalQuery.Document document, String modelVersion,String field) {
        return db.query("SELECT source_version,raw_sha,locator_ref,vector_sha,vector_data "
                        + "FROM con4_document_vector WHERE source_id=? AND document_id=? "
                        + "AND document_version=? AND field_name=? AND model_version=?",
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBytes(5)), document.sourceId(), document.documentId(),
                document.documentVersion(), field,modelVersion);
    }

    private List<PatentLexicalQuery.Document> documents(ProductSourceAvailability.Active active,String field) {
        var documents = "TITLE".equals(field) ? titles.load(active) : titles.loadField(active,field);
        if (documents == null || documents.stream().anyMatch(doc -> doc == null || doc.fields() == null
                || !doc.fields().keySet().equals(java.util.Set.of(field)) || doc.fields().get(field) == null
                || doc.fields().get(field).isBlank()))
            throw new IllegalStateException("Exact selected source field content required");
        return documents.stream().sorted(Comparator.comparing(PatentLexicalQuery.Document::documentId)
                .thenComparing(PatentLexicalQuery.Document::documentVersion)).toList();
    }

    private static void field(String field) {
        if (field == null) throw new IllegalArgumentException("Exact semantic field required");
        ProductCapturedPatentFields.validate(java.util.Set.of(field));
    }

    private static boolean sameEvidence(Stored stored, PatentLexicalQuery.Document document) {
        return stored.sourceVersion().equals(document.sourceVersion())
                && stored.rawSha().equals(document.rawSha())
                && stored.locatorRef().equals(document.locatorRef());
    }

    private List<Receipt> prior(String requestId, String requestSha) {
        List<Receipt> rows = db.query("SELECT build_id,source_id,field_name,model_version,document_count,"
                        + "embedded_count,reused_count,build_sha,request_sha FROM con4_vector_build WHERE request_id=?",
                (rs, row) -> {
                    if (!rs.getString(9).equals(requestSha))
                        throw new IllegalStateException("Semantic build request conflicts with prior input");
                    return new Receipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8));
                }, requestId);
        if (rows.size() > 1) throw new IllegalStateException("Semantic build receipt is not unique");
        return rows;
    }

    private float[] embedding(String field,String text) {
        return ("TITLE".equals(field) ? embeddings.embed(List.of(text)) : embeddings.embedComplete(List.of(text))).getFirst();
    }

    private String modelVersion(String field) {
        var model = embeddings.model();
        String base = model.modelId() + "@" + model.modelRevision() + "/" + model.normalization();
        return "TITLE".equals(field) ? base : base + "/" + PatentEmbeddingProvider.COMPLETE_REPRESENTATION_VERSION
                + "/W" + model.maxTokens() + "/D" + model.dimension();
    }
    String fieldModelVersion(String field) {
        field(field);
        return modelVersion(field);
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length != right.length) throw new IllegalStateException("Vector dimensions differ");
        double value = 0;
        for (int index = 0; index < left.length; index++) value += left[index] * right[index];
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static void require(String source, String request, String actor) {
        if (source == null || source.isBlank() || actor == null || actor.isBlank()
                || request == null || !request.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact semantic build identity required");
    }
}
