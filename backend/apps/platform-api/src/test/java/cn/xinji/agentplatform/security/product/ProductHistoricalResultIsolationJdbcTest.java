package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual file-H2/JdbcTemplate. Engineering-owned rows ONLY, not the three real historical results.
 * No JDBC mocks, no current DB credentials, no protected originals, no official COMMITTED states. */
class ProductHistoricalResultIsolationJdbcTest {
    @TempDir Path temporary;
    private static final String MODEL="own-model@r1/MEAN_POOL_L2/v1/ALL_TOKEN_WINDOWS_WEIGHTED_MEAN_L2/v1/W8/D2";
    private static final String METHOD="HYBRID_FIELD_RRF/v1";
    private static final String ORDER="FIELD_RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1";
    private static final Instant TIME=Instant.parse("2026-10-01T00:00:00Z");
    private static final ProductPatentSearchEvidenceCodec CODEC=new ProductPatentSearchEvidenceCodec(new ObjectMapper());
    private static final List<String> TABLES=List.of("con3_task","con3_search_input","con3_execution_attempt",
            "con4_search_attempt","con4_result_set","con4_search_request","con4_search_source","con4_result_member",
            "con4_search_field_scope","con4_result_field_evidence","con4_selection_revision","con4_selection_member",
            "con4_vector_build","con4_source_verification","con4_embedding_model","con4_document_vector_binding","con4_document_vector");
    private record Copy(JdbcTemplate db,String url,String id,String sha,String vectorSha,String buildSha,Path originals) { }

    @Test void missingWitnessRejectsAndActualRecoveryReportPreservesSamePointRecords() throws Exception {
        Copy copy=create(false); String before=snapshot(copy.db());
        copy.db().execute("CHECKPOINT SYNC"); // Reopen the actual on-disk store, not reconstructed/mock rows.
        JdbcTemplate reopened=jdbc(copy.url());
        assertEquals(before,snapshot(reopened));
        var failure=assertThrows(ProductHistoricalResultIsolation.Unverified.class,
                ()->new ProductHistoricalResultIsolation(reopened).require(copy.id()));
        assertEquals(ProductHistoricalResultIsolation.CODE,failure.getReason());
        var verifier=new ProductPatentSearchRecoveryVerifier(reopened,copy.originals(),10000);
        assertThrows(ProductHistoricalResultIsolation.Unverified.class,verifier::verify);
        var report=verifier.report();
        assertFalse(report.overallIntegrityVerified()); assertFalse(report.fullOriginalValidationCompleted());
        assertEquals("EXACT_TUPLE_BUILD_MISSING",report.gaps().getFirst().missing());
        assertEquals("CLAIMS",report.gaps().getFirst().tuple().field());
        assertTrue(report.dependencyImpacts().stream().anyMatch(s->s.startsWith("SELECTION:")));
        assertEquals(copy.sha(),reopened.queryForObject("SELECT result_sha FROM con4_result_set",String.class));
        assertEquals(before,snapshot(reopened));
        try(var originals=Files.list(copy.originals())) {assertEquals(0,originals.count());}
        save("missing",copy,before,snapshot(reopened),CODEC.json(report));
    }
    @Test void independentlyClosedEngineeringTupleIsUsableWithRealSqlAndNoRecordChanges() throws Exception {
        Copy copy=create(true); String before=snapshot(copy.db());
        var isolation=new ProductHistoricalResultIsolation(copy.db());
        assertDoesNotThrow(()->isolation.require(copy.id())); assertTrue(isolation.inspect(copy.id()).isEmpty());
        assertEquals(copy.sha(),new ProductPatentSearchReceipts(copy.db()).read(copy.id()).resultSha());
        assertEquals(before,snapshot(copy.db()));
        save("closed",copy,before,snapshot(copy.db()),"CORE_AND_RECEIPT_USABLE_NOT_FULL_ORIGINAL_RECOVERY");
    }
    @Test void badStoredDigestRemainsHardRejectionInActualSqlRecoveryReport() throws Exception {
        Copy copy=create(false);
        copy.db().update("UPDATE con4_result_set SET result_sha=? WHERE result_set_id=?","f".repeat(64),copy.id());
        String before=snapshot(copy.db());
        assertThrows(IllegalStateException.class,()->new ProductHistoricalResultIsolation(copy.db()).require(copy.id()));
        var verifier=new ProductPatentSearchRecoveryVerifier(copy.db(),copy.originals(),10000);
        assertThrows(IllegalStateException.class,verifier::report);
        assertEquals(before,snapshot(copy.db()));
        save("bad-digest",copy,before,snapshot(copy.db()),"HARD_REJECTION_NOT_MISSING_WITNESS_REPORT");
    }
    private Copy create(boolean closed) throws Exception {
        String url="jdbc:h2:file:"+temporary.resolve("own-engineering").toAbsolutePath().toString().replace('\\','/')
                +";DB_CLOSE_ON_EXIT=FALSE";
        JdbcTemplate db=jdbc(url); schema(db);
        String id=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString(),task=UUID.randomUUID().toString();
        String memberId=UUID.randomUUID().toString(),selection=UUID.randomUUID().toString();
        String locator="own-engineering.json#json-pointer=/claims",rawSha=ProductVectorCodec.sha("OWN CLAIM TEXT".getBytes(StandardCharsets.UTF_8));
        byte[] vector=ProductVectorCodec.encode(new float[]{1,0},2);
        String vectorSha=ProductVectorCodec.sha(vector),buildSha=CODEC.digest(vectorSha);
        var member=new ProductPatentSearchExecution.Member(memberId,0,"OWN_SOURCE","OWN_DOC","OWN_DOCUMENT_VERSION",
                "OWN_SOURCE_VERSION",rawSha,locator,Set.of("CLAIMS"),List.of("own"),1.0,Map.of("CLAIMS",1.0),
                1,1.0/61,1,1.0/61,null,null);
        var facts=new ProductPatentFieldResultEvidence.Fields(Map.of("CLAIMS",locator),
                Map.of("CLAIMS",new ProductPatentFieldResultEvidence.Signal(1,1,locator,MODEL)));
        var bundle=new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,List.of("CLAIMS"),
                Map.of("CLAIMS",MODEL),METHOD,ORDER,"OWN_RERANK/v1","RERANK_FAILED",Map.of(memberId,facts));
        String sha=ProductPatentFieldResultEvidence.digest(CODEC,bundle,List.of(member),false);
        Timestamp expiry=Timestamp.from(TIME.plusSeconds(3600)),created=Timestamp.from(TIME);
        insert(db,"con3_task","task_id,organization_id,version",task,"OWN_ORGANIZATION",1);
        insert(db,"con3_search_input","input_id,task_id,task_version,content_sha,query_text,expires_at","OWN_INPUT",task,1,"OWN_INPUT_SHA","own",expiry);
        insert(db,"con3_execution_attempt","attempt_id,task_id,input_id,task_version,fencing_token,worker_ref",attempt,task,"OWN_INPUT",1,1,"OWN_WORKER");
        insert(db,"con4_search_attempt","attempt_id,task_id,task_version,fencing_token,input_revision,input_sha,actor_ref,organization_id,"
                +"expires_at,status,original_query,created_at,method_version,scope_sha,rerank_model_version,rerank_status,request_sha",
                attempt,task,1,1,"OWN_INPUT","OWN_INPUT_SHA","OWN_WORKER","OWN_ORGANIZATION",expiry,"SUCCEEDED","own",created,
                METHOD,"OWN_SCOPE","OWN_RERANK/v1","RERANK_FAILED","OWN_REQUEST_SHA");
        insert(db,"con4_result_set","result_set_id,attempt_id,candidate_count,truncated,result_sha,confidence_status,confidence_rule_version,"
                +"confidence_reason,total_reliability,digest_version,ordering_version",id,attempt,1,0,sha,"LOW_CONFIDENCE",
                "OWN_RULE","ENGINEERING_ONLY","EXACT",ProductPatentFieldResultEvidence.DIGEST_VERSION,ORDER);
        insert(db,"con4_search_request","result_set_id,attempt_id,request_sha",id,attempt,"OWN_REQUEST_SHA");
        insert(db,"con4_search_source","attempt_id,source_id,contract_version,decision_id,source_epoch,status,error_code",
                attempt,"OWN_SOURCE","OWN_CONTRACT","OWN_DECISION",7,"SUCCEEDED",null);
        insert(db,"con4_result_member","result_set_id,attempt_id,member_id,ordinal,source_id,document_id,document_version,source_version,"
                +"raw_sha,locator_ref,match_fields,matched_terms_json,lexical_score,field_scores_json,lexical_rank,semantic_score,semantic_rank,"
                +"fusion_score,rerank_score,rerank_rank",id,attempt,memberId,0,"OWN_SOURCE","OWN_DOC","OWN_DOCUMENT_VERSION",
                "OWN_SOURCE_VERSION",rawSha,locator,"CLAIMS","[\"own\"]",1.0,"{\"CLAIMS\":1.0}",1,1.0/61,1,1.0/61,null,null);
        insert(db,"con4_search_field_scope","attempt_id,result_set_id,digest_version,scope_version,text_fields_json,models_json,scope_sha",
                attempt,id,ProductPatentFieldResultEvidence.DIGEST_VERSION,"SEARCH_FIELD_SCOPE/v2","[\"CLAIMS\"]",CODEC.json(bundle.models()),"OWN_SCOPE");
        String json=CODEC.json(ProductPatentFieldResultEvidence.canonical(facts));
        insert(db,"con4_result_field_evidence","result_set_id,member_id,evidence_version,fields_json,evidence_sha",id,memberId,
                ProductPatentFieldResultEvidence.VERSION,json,CODEC.digest(ProductPatentFieldResultEvidence.VERSION,json));
        insert(db,"con4_selection_revision","selection_id,task_id,task_version,result_set_id,result_sha,result_set_version,expires_at",
                selection,task,1,id,sha,1,expiry);
        insert(db,"con4_selection_member","selection_id,result_set_id,member_id,ordinal,source_id,document_id,document_version,source_version,raw_sha,locator_ref",
                selection,id,memberId,0,"OWN_SOURCE","OWN_DOC","OWN_DOCUMENT_VERSION","OWN_SOURCE_VERSION",rawSha,locator);
        insert(db,"con4_source_verification","source_id,contract_version,source_version","OWN_SOURCE","OWN_CONTRACT","OWN_SOURCE_VERSION");
        insert(db,"con4_embedding_model","model_version,provider,model_id,model_revision,model_sha,tokenizer_sha,vector_dimension,max_tokens,normalization,license_id",
                MODEL,"OWN_PROVIDER","own-model","r1","a".repeat(64),"b".repeat(64),2,8,"MEAN_POOL_L2/v1","OWN_ENGINEERING_LICENSE");
        insert(db,"con4_document_vector_binding","source_id,source_version,document_id,document_version,field_name,model_version,vector_sha",
                "OWN_SOURCE","OWN_SOURCE_VERSION","OWN_DOC","OWN_DOCUMENT_VERSION","CLAIMS",MODEL,vectorSha);
        insert(db,"con4_document_vector","source_id,document_id,document_version,field_name,model_version,vector_sha,vector_data",
                "OWN_SOURCE","OWN_DOC","OWN_DOCUMENT_VERSION","CLAIMS",MODEL,vectorSha,vector);
        if(closed) insert(db,"con4_vector_build","source_id,contract_version,decision_id,source_epoch,field_name,model_version,build_sha,document_count,completed_at",
                "OWN_SOURCE","OWN_CONTRACT","OWN_DECISION",7,"CLAIMS",MODEL,buildSha,1,created);
        Path originals=Files.createDirectory(temporary.resolve("empty-self-owned-originals"));
        assertTrue(Files.isRegularFile(temporary.resolve("own-engineering.mv.db")));
        return new Copy(db,url,id,sha,vectorSha,buildSha,originals);
    }
    private static JdbcTemplate jdbc(String url) {
        if(!url.startsWith("jdbc:h2:file:")) throw new IllegalArgumentException("Own file-H2 only");
        JdbcDataSource source=new JdbcDataSource();source.setURL(url);source.setUser("sa");source.setPassword("");
        return new JdbcTemplate(source);
    }
    private static void insert(JdbcTemplate db,String table,String columns,Object... values) {
        db.update("INSERT INTO "+table+"("+columns+") VALUES("+String.join(",",Collections.nCopies(values.length,"?"))+")",values);
    }
    private static void schema(JdbcTemplate db) {
        String[] ddl={
            "con3_task(task_id VARCHAR PRIMARY KEY,organization_id VARCHAR,version BIGINT)",
            "con3_search_input(input_id VARCHAR PRIMARY KEY,task_id VARCHAR,task_version BIGINT,content_sha VARCHAR,query_text VARCHAR,expires_at TIMESTAMP)",
            "con3_execution_attempt(attempt_id VARCHAR PRIMARY KEY,task_id VARCHAR,input_id VARCHAR,task_version BIGINT,fencing_token BIGINT,worker_ref VARCHAR)",
            "con4_search_attempt(attempt_id VARCHAR PRIMARY KEY,task_id VARCHAR,task_version BIGINT,fencing_token BIGINT,input_revision VARCHAR,input_sha VARCHAR,actor_ref VARCHAR,organization_id VARCHAR,expires_at TIMESTAMP,status VARCHAR,original_query VARCHAR,created_at TIMESTAMP,method_version VARCHAR,scope_sha VARCHAR,rerank_model_version VARCHAR,rerank_status VARCHAR,request_sha VARCHAR)",
            "con4_result_set(result_set_id VARCHAR PRIMARY KEY,attempt_id VARCHAR,candidate_count INT,truncated INT,result_sha VARCHAR,confidence_status VARCHAR,confidence_rule_version VARCHAR,confidence_reason VARCHAR,total_reliability VARCHAR,digest_version VARCHAR,ordering_version VARCHAR)",
            "con4_search_request(result_set_id VARCHAR,attempt_id VARCHAR,request_sha VARCHAR)",
            "con4_search_source(attempt_id VARCHAR,source_id VARCHAR,contract_version VARCHAR,decision_id VARCHAR,source_epoch BIGINT,status VARCHAR,error_code VARCHAR)",
            "con4_result_member(result_set_id VARCHAR,attempt_id VARCHAR,member_id VARCHAR PRIMARY KEY,ordinal BIGINT,source_id VARCHAR,document_id VARCHAR,document_version VARCHAR,source_version VARCHAR,raw_sha VARCHAR,locator_ref VARCHAR,match_fields VARCHAR,matched_terms_json VARCHAR,lexical_score DOUBLE,field_scores_json VARCHAR,lexical_rank INT,semantic_score DOUBLE,semantic_rank INT,fusion_score DOUBLE,rerank_score DOUBLE,rerank_rank INT)",
            "con4_search_field_scope(attempt_id VARCHAR,result_set_id VARCHAR,digest_version VARCHAR,scope_version VARCHAR,text_fields_json VARCHAR,models_json VARCHAR,scope_sha VARCHAR)",
            "con4_result_field_evidence(result_set_id VARCHAR,member_id VARCHAR,evidence_version VARCHAR,fields_json VARCHAR,evidence_sha VARCHAR)",
            "con4_selection_revision(selection_id VARCHAR PRIMARY KEY,task_id VARCHAR,task_version BIGINT,result_set_id VARCHAR,result_sha VARCHAR,result_set_version BIGINT,expires_at TIMESTAMP)",
            "con4_selection_member(selection_id VARCHAR,result_set_id VARCHAR,member_id VARCHAR,ordinal BIGINT,source_id VARCHAR,document_id VARCHAR,document_version VARCHAR,source_version VARCHAR,raw_sha VARCHAR,locator_ref VARCHAR)",
            "con4_vector_build(source_id VARCHAR,contract_version VARCHAR,decision_id VARCHAR,source_epoch BIGINT,field_name VARCHAR,model_version VARCHAR,build_sha VARCHAR,document_count INT,completed_at TIMESTAMP)",
            "con4_source_verification(source_id VARCHAR,contract_version VARCHAR,source_version VARCHAR)",
            "con4_embedding_model(model_version VARCHAR PRIMARY KEY,provider VARCHAR,model_id VARCHAR,model_revision VARCHAR,model_sha VARCHAR,tokenizer_sha VARCHAR,vector_dimension INT,max_tokens INT,normalization VARCHAR,license_id VARCHAR)",
            "con4_document_vector_binding(source_id VARCHAR,source_version VARCHAR,document_id VARCHAR,document_version VARCHAR,field_name VARCHAR,model_version VARCHAR,vector_sha VARCHAR)",
            "con4_document_vector(source_id VARCHAR,document_id VARCHAR,document_version VARCHAR,field_name VARCHAR,model_version VARCHAR,vector_sha VARCHAR,vector_data VARBINARY)"
        };
        for(String definition:ddl) db.execute("CREATE TABLE "+definition);
    }
    private static String snapshot(JdbcTemplate db) {
        Map<String,List<List<String>>> tables=new TreeMap<>();
        for(String table:TABLES) {
            List<List<String>> rows=db.query("SELECT * FROM "+table,(rs,n)-> {
                List<String> row=new ArrayList<>();
                for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                    Object v=rs.getObject(i);row.add(v instanceof byte[] b ? HexFormat.of().formatHex(b):Objects.toString(v,null));
                }
                return row;
            });
            rows.sort(Comparator.comparing(CODEC::json));tables.put(table,rows);
        }
        return CODEC.json(tables);
    }
    private void save(String name,Copy copy,String before,String after,String report) throws Exception {
        Path out=Files.createDirectories(temporary.resolve("engineering-evidence"));
        Files.writeString(out.resolve(name+"-before.json"),before);Files.writeString(out.resolve(name+"-after.json"),after);
        Files.writeString(out.resolve(name+"-report.txt"),report);
        String beforeSha=ProductVectorCodec.sha(before.getBytes(StandardCharsets.UTF_8));
        String afterSha=ProductVectorCodec.sha(after.getBytes(StandardCharsets.UTF_8));
        Files.writeString(out.resolve(name+"-receipt.json"),CODEC.json(Map.of("engineeringOwnSample",true,
                "realThreeHistoricalSamples",false,"engine","ACTUAL_FILE_H2_REAL_JDBCTEMPLATE","resultId",copy.id(),
                "originalSnapshotSha",beforeSha,"afterSnapshotSha",afterSha,"samePointRecordsUnchanged",beforeSha.equals(afterSha))));
        // Export only our generated engineering evidence when explicitly requested.
        String configured=System.getProperty("isolation.jdbc.evidence.dir");
        if(configured!=null && !configured.isBlank()) {
            Path output=Files.createDirectories(Path.of(configured).toAbsolutePath());
            for(String suffix:List.of("-before.json","-after.json","-report.txt","-receipt.json"))
                Files.copy(out.resolve(name+suffix),output.resolve(name+suffix),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public static void main(String[] args) {
        ConfigurationParameters parameters=new ConfigurationParameters() {
            public Optional<String> get(String key){return Optional.empty();}
            public Optional<Boolean> getBoolean(String key){return Optional.empty();}
            public int size(){return 0;}public Set<String> keySet(){return Set.of();}
        };
        var selector=DiscoverySelectors.selectClass(ProductHistoricalResultIsolationJdbcTest.class);
        EngineDiscoveryRequest request=new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type){return type.isInstance(selector)?List.of(type.cast(selector)):List.of();}
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type){return List.of();}
            public ConfigurationParameters getConfigurationParameters(){return parameters;}
        };
        var engine=new JupiterTestEngine();var root=engine.discover(request,UniqueId.forEngine(engine.getId()));int[] counts={0,0,0};
        engine.execute(new ExecutionRequest(root,new EngineExecutionListener() {
            public void executionSkipped(TestDescriptor d,String reason){if(d.isTest())counts[2]++;System.out.println("SKIPPED "+d.getDisplayName());}
            public void executionFinished(TestDescriptor d,TestExecutionResult r) {
                if(d.isTest() && r.getStatus()==TestExecutionResult.Status.SUCCESSFUL){counts[0]++;System.out.println("PASSED "+d.getDisplayName());}
                if(r.getStatus()==TestExecutionResult.Status.FAILED){counts[1]++;r.getThrowable().orElseThrow().printStackTrace(System.out);}
            }
        },parameters));
        System.out.printf("successfulTests=%d failedDescriptors=%d skippedTests=%d ACTUAL_FILE_H2_NOT_REAL_THREE_HISTORY%n",counts[0],counts[1],counts[2]);
        if(counts[0]!=3 || counts[1]!=0 || counts[2]!=0)System.exit(1);
    }
}

