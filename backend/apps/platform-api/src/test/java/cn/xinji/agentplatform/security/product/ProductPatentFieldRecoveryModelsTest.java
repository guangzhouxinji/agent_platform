package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mechanical snapshot/vector corruption C. No real model inference, Source activation or benchmark. */
class ProductPatentFieldRecoveryModelsTest {
    @Test void historicalFieldBuildBindsExactModelVersionOriginalLocatorAndVectorDigest() {
        var source=new JdbcDataSource(); source.setURL("jdbc:h2:mem:field-model-recovery-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(source); var codec=new ProductPatentSearchEvidenceCodec(new ObjectMapper());
        db.execute("CREATE TABLE con4_embedding_model(model_version VARCHAR,provider VARCHAR,model_id VARCHAR,"
                + "model_revision VARCHAR,model_sha VARCHAR,tokenizer_sha VARCHAR,vector_dimension INT,max_tokens INT,normalization VARCHAR,license_id VARCHAR)");
        db.execute("CREATE TABLE con4_search_attempt(attempt_id VARCHAR,created_at TIMESTAMP)");
        db.execute("CREATE TABLE con4_search_source(attempt_id VARCHAR,source_id VARCHAR,contract_version VARCHAR,decision_id VARCHAR,source_epoch BIGINT)");
        db.execute("CREATE TABLE con4_vector_build(source_id VARCHAR,contract_version VARCHAR,decision_id VARCHAR,source_epoch BIGINT,"
                + "field_name VARCHAR,model_version VARCHAR,build_sha VARCHAR,document_count INT,completed_at TIMESTAMP)");
        db.execute("CREATE TABLE con4_document_vector_binding(source_id VARCHAR,document_id VARCHAR,document_version VARCHAR,field_name VARCHAR,"
                + "model_version VARCHAR,source_version VARCHAR,raw_sha VARCHAR,locator_ref VARCHAR,vector_sha VARCHAR)");
        db.execute("CREATE TABLE con4_document_vector(source_id VARCHAR,document_id VARCHAR,document_version VARCHAR,field_name VARCHAR,"
                + "model_version VARCHAR,vector_sha VARCHAR,vector_data BLOB)");
        db.update("INSERT INTO con4_search_attempt VALUES('attempt',TIMESTAMP '2026-10-02 08:00:00')");
        db.update("INSERT INTO con4_search_source VALUES('attempt','source','contract','decision',7)");
        String model="mechanical-model@revision/L2/"+PatentEmbeddingProvider.COMPLETE_REPRESENTATION_VERSION+"/W512/D2";
        byte[] bytes=ProductVectorCodec.encode(new float[]{1,0},2); String vectorSha=ProductVectorCodec.sha(bytes);
        db.update("INSERT INTO con4_embedding_model VALUES(?,'mechanical','mechanical-model','revision',?,?,2,512,'L2','mechanical-C')",
                model,"a".repeat(64),"b".repeat(64));
        db.update("INSERT INTO con4_vector_build VALUES('source','contract','decision',7,'ABSTRACT',?,?,1,TIMESTAMP '2026-10-02 07:00:00')",
                model,codec.digest(vectorSha));
        db.update("INSERT INTO con4_document_vector_binding VALUES('source','doc','v1','ABSTRACT',?,'capture',?,'raw/doc#abstract',?)",
                model,"c".repeat(64),vectorSha);
        db.update("INSERT INTO con4_document_vector VALUES('source','doc','v1','ABSTRACT',?,?,?)",model,vectorSha,bytes);
        var doc=new PatentLexicalQuery.Document("source","doc","v1","capture","c".repeat(64),"raw/doc#abstract",
                Map.of("ABSTRACT","mechanical projection"));
        var originals=Map.of("source\0doc\0v1",new PatentFieldSemanticFusion.DocumentEvidence(doc,Map.of("ABSTRACT","raw/doc#abstract")));
        var bundle=new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,List.of("ABSTRACT"),Map.of("ABSTRACT",model),
                "HYBRID_FIELD_RRF/v1","FIELD_RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1",null,null,Map.of());
        var receipt=new ProductPatentSearchExecution.Receipt("attempt",1,"result","HYBRID_FIELD_RRF/v1",0,0,false,"d".repeat(64),
                "LOW_CONFIDENCE","FIELD_CALIBRATION_UNAVAILABLE/v1","FIELD_CALIBRATION_UNAVAILABLE","SUCCEEDED","EXACT",
                List.of(new ProductPatentSearchSourceBatch.Outcome("source","contract","decision",7,"SUCCEEDED",null)),
                ProductPatentFieldResultEvidence.DIGEST_VERSION);
        var verifier=new ProductPatentFieldRecoveryModels(db,codec);
        verifier.verify(receipt,bundle,originals);
        db.update("UPDATE con4_vector_build SET source_epoch=6");
        assertThatThrownBy(() -> verifier.verify(receipt,bundle,originals)).hasMessage("Restored field model/vector evidence inconsistent");
        db.update("UPDATE con4_vector_build SET source_epoch=7,completed_at=TIMESTAMP '2026-10-02 09:00:00'");
        assertThatThrownBy(() -> verifier.verify(receipt,bundle,originals)).hasMessage("Restored field model/vector evidence inconsistent");
        db.update("UPDATE con4_vector_build SET completed_at=TIMESTAMP '2026-10-02 07:00:00',build_sha=?","e".repeat(64));
        assertThatThrownBy(() -> verifier.verify(receipt,bundle,originals)).hasMessage("Restored field model/vector evidence inconsistent");
        db.update("UPDATE con4_vector_build SET build_sha=?",codec.digest(vectorSha));
        db.update("UPDATE con4_document_vector_binding SET locator_ref='raw/doc#title'");
        assertThatThrownBy(() -> verifier.verify(receipt,bundle,originals)).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_document_vector_binding SET locator_ref='raw/doc#abstract'");
        db.update("UPDATE con4_document_vector SET vector_data=?",ProductVectorCodec.encode(new float[]{0,1},2));
        assertThatThrownBy(() -> verifier.verify(receipt,bundle,originals)).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_document_vector SET vector_data=?",bytes);
        verifier.verify(receipt,bundle,originals);
    }
}
