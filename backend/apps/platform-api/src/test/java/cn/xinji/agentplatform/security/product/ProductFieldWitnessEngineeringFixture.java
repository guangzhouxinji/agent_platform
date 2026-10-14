package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Solely new, self-owned in-memory fixtures for these two tests, never historical/business repair.
 * Source authority is a mechanical port double. The local complete-text projection is NOT a deployed
 * model, third-party patent original, real source admission or an external model invocation.
 * Models/vectors/bindings/build receipts are persisted ONLY by actual ProductSemanticIndex code. */
final class ProductFieldWitnessEngineeringFixture {
    record Witness(ProductSemanticIndex index, Map<String, ProductSemanticIndex.Receipt> builds,
                   PatentFieldSemanticFusion.DocumentEvidence original) {
        Map<String, String> models() {
            var result=new TreeMap<String,String>();
            builds.forEach((field,receipt)->result.put(field,receipt.modelVersion()));
            return Map.copyOf(result);
        }
    }

    static Witness build(JdbcDataSource datasource, JdbcTemplate db,
            ProductSourceAvailability.Active active, PatentFieldSemanticFusion.DocumentEvidence original,
            Set<String> fields, Clock clock) throws Exception {
        try(var connection=datasource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .matches("jdbc:h2:mem:(field-execution|source-results)-[0-9a-f-]{36}");
        }
        var document=original.document();
        assertThat(document.sourceId()).isEqualTo(active.sourceId());
        assertThat(document.sourceVersion()).isEqualTo(active.sourceVersion());
        assertThat(document.fields().keySet()).isEqualTo(fields);
        assertThat(original.fieldLocators().keySet()).isEqualTo(fields);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_vector_build WHERE source_id=?",
                Integer.class,active.sourceId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_verification WHERE source_id=? AND contract_version=?",
                Integer.class,active.sourceId(),active.contractVersion())).isZero();
        // Explicit metadata for exactly this newly authored one-document engineering source pool.
        db.update("INSERT INTO con4_source_verification(verification_id,source_id,contract_version,source_version,"
                + "candidate_row_version,manifest_ref,manifest_sha,records_sha,inventory_sha,record_count,raw_count,"
                + "verifier_ref,verified_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),active.sourceId(),active.contractVersion(),active.sourceVersion(),
                active.rowVersion(),active.manifestRef(),active.manifestSha(),active.recordsSha(),
                ProductPatentSearchEvidenceCodec.digestValues(document.rawSha(),document.locatorRef()),
                1,1,"SELF_OWNED_FIELD_WITNESS_ENGINEERING_NO_EXTERNAL_MODEL",Timestamp.from(clock.instant()));
        var authority=mock(ProductSourceAvailability.class);
        var watermark=mock(ProductSourceWatermark.class);
        var content=mock(ProductCollectedPatentTitles.class);
        for(String field:fields) {
            when(authority.require(active.sourceId(),field,"QUERY")).thenReturn(active);
            var projection=new PatentLexicalQuery.Document(document.sourceId(),document.documentId(),
                    document.documentVersion(),document.sourceVersion(),document.rawSha(),
                    original.fieldLocators().get(field),Map.of(field,document.fields().get(field)));
            when(content.loadField(active,field)).thenReturn(List.of(projection));
        }
        var provider=new CompleteEngineeringProjection();
        var index=new ProductSemanticIndex(db,new TransactionTemplate(new DataSourceTransactionManager(datasource)),
                authority,watermark,content,provider,clock);
        var builds=new TreeMap<String,ProductSemanticIndex.Receipt>();
        for(String field:new TreeSet<>(fields)) {
            var receipt=index.buildField(active.sourceId(),field,"owned-field-build:"+UUID.randomUUID(),
                    "SELF_OWNED_FIELD_WITNESS_ENGINEERING_NO_EXTERNAL_MODEL");
            assertThat(receipt.documentCount()).isEqualTo(1);
            assertThat(receipt.embeddedCount()).isEqualTo(1);
            assertThat(receipt.reusedCount()).isZero();
            index.validateField(active,field); // Actual current-build/vector validation, never a mock return.
            assertThat(receipt.modelVersion()).isEqualTo(index.fieldModelVersion(field));
            var currentBuild=db.queryForMap("SELECT build_id,build_sha,document_count,completed_at FROM con4_vector_build "
                    + "WHERE source_id=? AND contract_version=? AND decision_id=? AND source_epoch=? "
                    + "AND field_name=? AND model_version=? AND completed_at<=?",
                    active.sourceId(),active.contractVersion(),active.decisionId(),active.epoch(),field,
                    receipt.modelVersion(),Timestamp.from(clock.instant()));
            assertThat(currentBuild.get("BUILD_ID")).isEqualTo(receipt.buildId());
            assertThat(currentBuild.get("BUILD_SHA")).isEqualTo(receipt.buildSha());
            assertThat(((Number)currentBuild.get("DOCUMENT_COUNT")).intValue()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT source_version FROM con4_source_verification "
                    + "WHERE source_id=? AND contract_version=?",String.class,active.sourceId(),active.contractVersion()))
                    .isEqualTo(document.sourceVersion());
            var model=provider.model();
            assertThat(db.queryForObject("SELECT model_sha FROM con4_embedding_model WHERE model_version=?",
                    String.class,receipt.modelVersion())).isEqualTo(model.modelSha256());
            assertThat(db.queryForObject("SELECT tokenizer_sha FROM con4_embedding_model WHERE model_version=?",
                    String.class,receipt.modelVersion())).isEqualTo(model.tokenizerSha256());
            assertThat(db.queryForObject("SELECT raw_sha FROM con4_document_vector_binding WHERE source_id=? "
                    + "AND source_version=? AND document_id=? AND document_version=? AND field_name=? AND model_version=?",
                    String.class,active.sourceId(),active.sourceVersion(),document.documentId(),document.documentVersion(),
                    field,receipt.modelVersion())).isEqualTo(document.rawSha());
            builds.put(field,receipt);
        }
        assertThat(provider.completeInputs).containsExactlyInAnyOrderElementsOf(document.fields().values());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_document_vector_binding WHERE source_id=? AND source_version=?",
                Integer.class,active.sourceId(),active.sourceVersion())).isEqualTo(fields.size());
        return new Witness(index,Map.copyOf(builds),original);
    }

    // Dedicated minimal fenced-request parents; never depend on another concurrently edited test class.
    static void executionParents(JdbcTemplate db) {
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36),organization_id VARCHAR(200),version BIGINT,"
                + "cancel_epoch BIGINT,state VARCHAR(24),expires_at TIMESTAMP,PRIMARY KEY(task_id),"
                + "UNIQUE(task_id,organization_id))");
        db.execute("CREATE TABLE con3_goal_current(task_id VARCHAR(36) PRIMARY KEY,goal_id VARCHAR(36),"
                + "revision BIGINT,task_version BIGINT)");
        db.execute("CREATE TABLE con3_search_input(input_id VARCHAR(36),task_id VARCHAR(36),goal_id VARCHAR(36),"
                + "goal_revision BIGINT,task_version BIGINT,query_text CLOB,source_scope_json CLOB,"
                + "requested_fields_json CLOB,content_sha VARCHAR(64),confirmed_at TIMESTAMP,expires_at TIMESTAMP,"
                + "PRIMARY KEY(input_id),UNIQUE(task_id,input_id))");
        db.execute("CREATE TABLE con3_execution_lease(task_id VARCHAR(36) PRIMARY KEY,attempt_id VARCHAR(36) UNIQUE,"
                + "input_id VARCHAR(36),task_version BIGINT,cancel_epoch BIGINT,fencing_token BIGINT,"
                + "worker_ref VARCHAR(200),status VARCHAR(24),lease_until TIMESTAMP,updated_at TIMESTAMP)");
        db.execute("CREATE TABLE con3_execution_dispatch(task_id VARCHAR(36),attempt_id VARCHAR(36),"
                + "fencing_token BIGINT,status VARCHAR(24))");
        db.execute("CREATE TABLE con4_source_contract(source_id VARCHAR(200),contract_version VARCHAR(100),"
                + "PRIMARY KEY(source_id,contract_version))");
        db.execute("CREATE TABLE con4_source_admission(decision_id VARCHAR(36),source_id VARCHAR(200),"
                + "contract_version VARCHAR(100),PRIMARY KEY(decision_id),"
                + "UNIQUE(decision_id,source_id,contract_version))");
    }
    static void executionInput(JdbcTemplate db,java.time.Instant now) {
        db.update("INSERT INTO con3_task VALUES(?,?,?,?,?,?)","task","org",2,0,"IN_PROGRESS",Timestamp.from(now.plusSeconds(1800)));
        db.update("INSERT INTO con3_goal_current VALUES(?,?,?,?)","task","goal",1,2);
        db.update("INSERT INTO con3_search_input VALUES(?,?,?,?,?,?,?,?,?,?,?,?)","input","task","goal",1,2,
                "mechanical engineering projection","[\"source\"]","[\"ABSTRACT\",\"CLAIMS\"]","b".repeat(64),
                Timestamp.from(now.minusSeconds(1)),Timestamp.from(now.plusSeconds(1800)),
                "{\"publicationFrom\":null,\"publicationTo\":null,\"ipcPrefixes\":[],\"cpcPrefixes\":[]}");
        db.update("INSERT INTO con3_execution_lease VALUES(?,?,?,?,?,?,?,?,?,?)","task","attempt","input",2,0,7,
                "worker","ACTIVE",Timestamp.from(now.plusSeconds(300)),Timestamp.from(now));
        db.update("INSERT INTO con4_source_contract VALUES('source','contract')");
        db.update("INSERT INTO con4_source_admission VALUES('decision','source','contract')");
    }

    private static final class CompleteEngineeringProjection implements PatentEmbeddingProvider {
        private final List<String> completeInputs=new ArrayList<>();
        public Model model() {
            return new Model("SELF_OWNED_MECHANICAL_NO_EXTERNAL_MODEL","owned-field-codepoint-projection", "engineering-v1",
                    ProductPatentSearchEvidenceCodec.digestValues("owned-full-codepoint-projection/v1"),
                    ProductPatentSearchEvidenceCodec.digestValues("all-codepoints-no-tokenizer-no-truncation/v1"),
                    3,512,"SELF_OWNED_L2/v1","ENGINEERING_ONLY_NO_BUSINESS_OR_MODEL_ACCURACY_CLAIM");
        }
        public List<float[]> embed(List<String> texts) {
            throw new AssertionError("These field fixtures must use the complete representation");
        }
        public List<float[]> embedComplete(List<String> texts) {
            completeInputs.addAll(texts);
            return texts.stream().map(text->{
                double[] sums={1,1,1};int[] codepoints=text.codePoints().toArray();
                for(int i=0;i<codepoints.length;i++)sums[i%3]+=codepoints[i]+1;
                double norm=Math.sqrt(sums[0]*sums[0]+sums[1]*sums[1]+sums[2]*sums[2]);
                return new float[]{(float)(sums[0]/norm),(float)(sums[1]/norm),(float)(sums[2]/norm)};
            }).toList();
        }
    }
    private ProductFieldWitnessEngineeringFixture() { }
}
