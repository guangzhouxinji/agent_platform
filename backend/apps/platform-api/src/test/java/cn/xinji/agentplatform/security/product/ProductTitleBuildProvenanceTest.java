package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Engineering-owned SQL facts. Not a model response, historical business backfill or COMMITTED result. */
class ProductTitleBuildProvenanceTest {
    final Instant time=Instant.parse("2026-10-07T01:00:00Z");
    final String sha="a".repeat(64);
    final ProductSourceAvailability.Active source=new ProductSourceAvailability.Active("owned","contract","v1","decision",2,1,"original",sha,sha);
    JdbcTemplate db() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:title-witness-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");var db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE con4_result_set(result_set_id VARCHAR(36) PRIMARY KEY)");
        db.execute("CREATE TABLE con4_vector_build(source_id VARCHAR(200),contract_version VARCHAR(200),decision_id VARCHAR(36),source_epoch BIGINT,field_name VARCHAR(32),model_version VARCHAR(300),build_sha VARCHAR(64),document_count INTEGER,completed_at TIMESTAMP)");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V104__product_title_result_build_provenance.sql"));}
        return db;
    }
    void build(JdbcTemplate db,String value,Instant at){db.update("INSERT INTO con4_vector_build VALUES('owned','contract','decision',2,'TITLE','owned-model',?,1,?)",value,Timestamp.from(at));}
    @Test void exactNewBindingSurvivesReadAndNeverInfersLegacyModel() throws Exception {
        var db=db();build(db,sha,time.minusSeconds(1));
        var witness=ProductTitleBuildProvenance.capture(db,List.of(source),Map.of(),"owned-model",time);
        db.update("INSERT INTO con4_result_set VALUES('owned-new-anchor')");
        ProductTitleBuildProvenance.store(db,"owned-new-anchor",witness);
        var outcome=new ProductPatentSearchSourceBatch.Outcome("owned","contract","decision",2,"SUCCEEDED",null);
        assertEquals(witness.getFirst(),ProductTitleBuildProvenance.read(db,"owned-new-anchor",outcome));
        assertNull(ProductTitleBuildProvenance.read(db,"legacy-without-record",outcome));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con4_result_title_build",Integer.class));
        db.update("UPDATE con4_result_title_build SET model_version='other-model'");
        assertThrows(IllegalStateException.class,()->ProductTitleBuildProvenance.read(db,"owned-new-anchor",outcome));
    }
    @Test void missingFutureConflictingAndWrongTupleWitnessesCloseTheWrite() throws Exception {
        var db=db();
        assertThrows(IllegalStateException.class,()->ProductTitleBuildProvenance.capture(db,List.of(source),Map.of(),"owned-model",time));
        build(db,sha,time.plusSeconds(1));
        assertThrows(IllegalStateException.class,()->ProductTitleBuildProvenance.capture(db,List.of(source),Map.of(),"owned-model",time));
        db.update("UPDATE con4_vector_build SET completed_at=?",Timestamp.from(time.minusSeconds(1)));build(db,"b".repeat(64),time.minusSeconds(1));
        assertThrows(IllegalStateException.class,()->ProductTitleBuildProvenance.capture(db,List.of(source),Map.of(),"owned-model",time));
        assertThrows(IllegalStateException.class,()->ProductTitleBuildProvenance.capture(db,List.of(source),Map.of(),"unregistered-model",time));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con4_result_title_build",Integer.class));
    }
    @Test void provenanceAndAnchorShareRollbackAndMissingSchemaFailsClosed() throws Exception {
        var db=db();build(db,sha,time.minusSeconds(1));var witness=ProductTitleBuildProvenance.capture(db,List.of(source),Map.of(),"owned-model",time);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{db.update("INSERT INTO con4_result_set VALUES('owned-rollback-anchor')");ProductTitleBuildProvenance.store(db,"owned-rollback-anchor",witness);throw new IllegalStateException("owned-tail-failure");}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con4_result_set",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con4_result_title_build",Integer.class));
        db.execute("DROP TABLE con4_result_title_build"); // This fresh owned test table only; never an operational rollback.
        assertThrows(IllegalStateException.class,()->ProductTitleBuildProvenance.requireSchema(db));
    }
}
