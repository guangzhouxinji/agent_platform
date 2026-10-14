package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Mechanical vector-storage C only, never a semantic model, Source activation or business result. */
class ProductSemanticFieldVectorsTest {
    private static final String SHA = "a".repeat(64);
    private static final String MODEL = "mechanical-field-binding";
    private JdbcTemplate database() throws Exception {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:field-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        var db = spy(new JdbcTemplate(source));
        db.execute("CREATE TABLE con4_source_admission(decision_id VARCHAR(36),source_id VARCHAR(200),"
                + "contract_version VARCHAR(100),PRIMARY KEY(decision_id),UNIQUE(decision_id,source_id,contract_version))");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/V067__product_search_semantic_index.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/V070__product_semantic_vector_snapshot_binding.sql")));
        }
        db.update("INSERT INTO con4_embedding_model VALUES(?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?)", MODEL,
                "MECHANICAL_C", "field-binding", "1", SHA, SHA, 3, 128, "MEAN_POOL_L2/v1", "NOT_A_MODEL",
                Timestamp.from(Instant.parse("2026-10-02T00:00:00Z")));
        return db;
    }
    private PatentLexicalQuery.Document document(String field) {
        return new PatentLexicalQuery.Document("source", "doc", "raw-v1", "capture-v1", SHA,
                "original.json#" + field, Map.of(field, "Mechanical text for binding validation"));
    }
    private void insert(JdbcTemplate db, String field, float[] vector) {
        byte[] bytes = ProductVectorCodec.encode(vector, 3); String sha = ProductVectorCodec.sha(bytes);
        var now = Timestamp.from(Instant.parse("2026-10-02T00:00:00Z"));
        db.update("INSERT INTO con4_document_vector VALUES(?,?,?,?,?,?,?,?,?,?,?)", "source", "doc", "raw-v1",
                field, MODEL, "capture-v1", SHA, document(field).locatorRef(), sha, bytes, now);
        db.update("INSERT INTO con4_document_vector_binding VALUES(?,?,?,?,?,?,?,?,?,?)", "source", "doc", "raw-v1",
                field, MODEL, "capture-v1", SHA, document(field).locatorRef(), sha, now);
    }
    private Map<String,float[]> read(JdbcTemplate db, String field) {
        return ProductSemanticVectors.loadField(db, "source", "capture-v1", List.of(document(field)), MODEL, 3, field);
    }

    @Test void selectsExactFieldRatherThanTitleAndUsesOneBoundSnapshotQuery() throws Exception {
        var db = database(); insert(db, "TITLE", new float[]{1,0,0}); insert(db, "ABSTRACT", new float[]{0,1,0});
        clearInvocations(db);
        assertThat(read(db,"ABSTRACT").get("doc")).containsExactly(0,1,0);
        verify(db, times(1)).query(org.mockito.ArgumentMatchers.contains("b.field_name=?"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class),
                org.mockito.ArgumentMatchers.eq("source"),org.mockito.ArgumentMatchers.eq("capture-v1"),
                org.mockito.ArgumentMatchers.eq(MODEL),org.mockito.ArgumentMatchers.eq("ABSTRACT"));
        assertThat(ProductSemanticVectors.load(db,"source","capture-v1",List.of(document("TITLE")),MODEL,3).get("doc"))
                .containsExactly(1,0,0);
    }

    @Test void allSupportedFieldsRemainIsolatedAndMissingFieldCannotBorrowTitle() throws Exception {
        var db = database(); insert(db,"TITLE",new float[]{1,0,0});
        assertThatThrownBy(() -> read(db,"CLAIMS")).isInstanceOf(ProductSemanticIndex.Unavailable.class);
        insert(db,"CLAIMS",new float[]{0,0,1}); insert(db,"DESCRIPTION",new float[]{0,1,0});
        assertThat(read(db,"CLAIMS").get("doc")).containsExactly(0,0,1);
        assertThat(read(db,"DESCRIPTION").get("doc")).containsExactly(0,1,0);
        assertThatThrownBy(() -> read(db,"ABSTRACT")).isInstanceOf(ProductSemanticIndex.Unavailable.class);
    }

    @Test void invalidFieldAndCrossFieldContentRejectBeforeDatabaseRead() throws Exception {
        var db = database(); clearInvocations(db);
        for (String field : List.of("LEGAL_STATUS", "", "TITLE' OR 1=1"))
            assertThatThrownBy(() -> read(db,field)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSemanticVectors.loadField(db,"source","capture-v1",List.of(document("TITLE")),MODEL,3,"ABSTRACT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductSemanticVectors.loadField(db,"source","capture-v1",List.of(document("TITLE")),MODEL,3,null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
    }

    @Test void fieldLocatorDigestVersionAndValuesRejectChangedStoredEvidence() throws Exception {
        var db = database(); insert(db,"ABSTRACT",new float[]{0,1,0});
        db.update("UPDATE con4_document_vector_binding SET locator_ref='original.json#TITLE'");
        assertThatThrownBy(() -> read(db,"ABSTRACT")).isInstanceOf(ProductSemanticIndex.Unavailable.class);
        db.update("UPDATE con4_document_vector_binding SET locator_ref=?,source_version='old'",document("ABSTRACT").locatorRef());
        assertThatThrownBy(() -> read(db,"ABSTRACT")).isInstanceOf(ProductSemanticIndex.Unavailable.class);
        db.update("UPDATE con4_document_vector_binding SET source_version='capture-v1',raw_sha=?","b".repeat(64));
        assertThatThrownBy(() -> read(db,"ABSTRACT")).isInstanceOf(ProductSemanticIndex.Unavailable.class);
        db.update("UPDATE con4_document_vector_binding SET raw_sha=?",SHA);
        db.update("UPDATE con4_document_vector SET vector_data=?",new byte[12]);
        assertThatThrownBy(() -> read(db,"ABSTRACT")).isInstanceOf(ProductSemanticIndex.Unavailable.class)
                .hasMessageContaining("digest");
    }

    @Test void nonfiniteWrongDimensionAndNonnormalizedFieldVectorsRejectDespiteMatchingDigests() throws Exception {
        for (byte[] bytes : List.of(
                java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(0).putFloat(0).array(),
                new byte[8],
                java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(2).putFloat(0).putFloat(0).array())) {
            var db = database(); insert(db,"CLAIMS",new float[]{1,0,0}); String sha = ProductVectorCodec.sha(bytes);
            db.update("UPDATE con4_document_vector SET vector_data=?,vector_sha=?",bytes,sha);
            db.update("UPDATE con4_document_vector_binding SET vector_sha=?",sha);
            assertThatThrownBy(() -> read(db,"CLAIMS")).isInstanceOf(ProductSemanticIndex.Unavailable.class)
                    .hasMessage("Semantic vector values changed");
        }
    }

    @Test void modelAndDocumentScopeDoNotBorrowOtherSnapshotsAndDuplicateIdentityRejects() throws Exception {
        var db = database(); insert(db,"DESCRIPTION",new float[]{1,0,0});
        assertThatThrownBy(() -> ProductSemanticVectors.loadField(db,"source","capture-v1",List.of(document("DESCRIPTION")),"old-model",3,"DESCRIPTION"))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class);
        assertThatThrownBy(() -> ProductSemanticVectors.loadField(db,"another-source","capture-v1",List.of(document("DESCRIPTION")),MODEL,3,"DESCRIPTION"))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class);
        assertThatThrownBy(() -> ProductSemanticVectors.loadField(db,"source","capture-v1",List.of(document("DESCRIPTION"),document("DESCRIPTION")),MODEL,3,"DESCRIPTION"))
                .isInstanceOf(ProductSemanticIndex.Unavailable.class).hasMessage("Semantic vector values changed");
    }
}
