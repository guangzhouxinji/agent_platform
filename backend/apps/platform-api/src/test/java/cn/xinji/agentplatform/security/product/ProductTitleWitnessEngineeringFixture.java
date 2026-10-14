package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Mechanical test model only. Actual index builds create the new fixture witness; not historical backfill. */
final class ProductTitleWitnessEngineeringFixture {
    static ProductSemanticIndex build(JdbcTemplate db,ProductSourceAvailability availability,ProductSourceWatermark watermark,
            ProductCollectedPatentTitles titles,ProductSourceAvailability.Active active,TransactionTemplate tx,Clock clock) throws Exception {
        try(var c=db.getDataSource().getConnection()) {
            for(String script:List.of("V067__product_search_semantic_index.sql","V070__product_semantic_vector_snapshot_binding.sql","V104__product_title_result_build_provenance.sql"))
                ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/"+script));
        }
        db.execute("CREATE TABLE con4_source_verification(source_id VARCHAR(200),contract_version VARCHAR(100),source_version VARCHAR(100),manifest_ref VARCHAR(500),manifest_sha VARCHAR(64),records_sha VARCHAR(64))");
        db.update("INSERT INTO con4_source_verification VALUES(?,?,?,?,?,?)",active.sourceId(),active.contractVersion(),active.sourceVersion(),active.manifestRef(),active.manifestSha(),active.recordsSha());
        PatentEmbeddingProvider provider=new PatentEmbeddingProvider(){
            public Model model(){return new Model("OWN_ENGINEERING_DETERMINISTIC","owned-title-witness","v1","a".repeat(64),"b".repeat(64),2,128,"MEAN_POOL_L2/v1","OWN_ENGINEERING_NOT_MODEL_APPROVAL");}
            public List<float[]> embed(List<String> texts){return texts.stream().map(text->new float[]{1f,0f}).toList();}
        };
        var actual=new ProductSemanticIndex(db,tx,availability,watermark,titles,provider,clock);
        actual.build(active.sourceId(),"owned-current-build","owned-engineering-fixture");
        return actual;
    }
}
