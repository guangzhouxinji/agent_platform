package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Engineering-owned, disconnected immutable recovery-copy rows. NOT actual historical data. */
final class ProductHistoricalResultIsolationFixture extends JdbcTemplate {
    final String id;
    ProductHistoricalResultIsolationFixture() { this(UUID.randomUUID().toString()); }
    ProductHistoricalResultIsolationFixture(String id) { this.id=id; }
    final Instant executed=Instant.parse("2026-10-01T00:00:00Z");
    final ProductPatentSearchEvidenceCodec codec=new ProductPatentSearchEvidenceCodec(new ObjectMapper());
    final byte[] vector=ProductVectorCodec.encode(new float[]{1,0},2);
    final String vectorSha=ProductVectorCodec.sha(vector), buildSha=codec.digest(vectorSha);
    final String locator="own-original.json#json-pointer=/claims", rawSha="c".repeat(64);
    final ProductPatentSearchExecution.Member member=new ProductPatentSearchExecution.Member("OWN_MEMBER",0,"OWN_SOURCE",
            "OWN_DOC","OLD_DOC_VERSION","OLD_SOURCE_VERSION",rawSha,locator,Set.of("CLAIMS"),List.of("own"),
            1.0,Map.of("CLAIMS",1.0),1,1.0/61,1,1.0/61,null,null);
    final ProductPatentFieldResultEvidence.Fields facts=new ProductPatentFieldResultEvidence.Fields(Map.of("CLAIMS",locator),
            Map.of("CLAIMS",new ProductPatentFieldResultEvidence.Signal(1,1,locator,"own-model@r1/MEAN_POOL_L2/v1/ALL_TOKEN_WINDOWS_WEIGHTED_MEAN_L2/v1/W8/D2")));
    final ProductPatentFieldResultEvidence.Bundle bundle=new ProductPatentFieldResultEvidence.Bundle(
            ProductPatentFieldResultEvidence.VERSION,List.of("CLAIMS"),Map.of("CLAIMS","own-model@r1/MEAN_POOL_L2/v1/ALL_TOKEN_WINDOWS_WEIGHTED_MEAN_L2/v1/W8/D2"),
            "HYBRID_FIELD_RRF/v1","FIELD_RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1","OWN_RERANK/v1","RERANK_FAILED",Map.of("OWN_MEMBER",facts));
    String resultSha=ProductPatentFieldResultEvidence.digest(codec,bundle,List.of(member),false);
    List<ProductHistoricalResultIsolation.Build> builds=List.of();
    boolean badVector,legacy,wrongTuple,modelTurns;
    int writes;
    final List<String> reads=new ArrayList<>();
    void closed() { builds=List.of(new ProductHistoricalResultIsolation.Build(buildSha,1,executed)); }
    byte[] originalBytes() {
        return (id+"\n"+resultSha+"\n"+vectorSha+"\nOWN_ENGINEERING_COPY\n"+codec.json(member)+"\n"+codec.json(ProductPatentFieldResultEvidence.canonical(facts))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Override public <T> T queryForObject(String sql,Class<T> type) {
        return type.cast(type==Long.class ? (Object)Long.valueOf(0) : Integer.valueOf(1));
    }
    @Override public <T> T execute(ConnectionCallback<T> action) {
        try {
            DatabaseMetaData metadata=(DatabaseMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{DatabaseMetaData.class},(p,m,a)-> {
                        if(m.getName().equals("getTables")) {
                            // Only the explicitly populated model-turn fixture table exists.
                            boolean exists=modelTurns && "CON3_MODEL_TURN".equals(a[2]);
                            boolean[] first={true};
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra)-> {
                                if(rm.getName().equals("close")) return null;
                                if(!rm.getName().equals("next")) throw new AssertionError(rm);
                                boolean found=first[0] && exists; first[0]=false; return found;
                            });
                        }
                        throw new AssertionError(m);
                    });
            Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},
                    (p,m,a)->switch(m.getName()) {case "getMetaData"->metadata;case "getSchema"->"OWN_COPY";default->throw new AssertionError(m);});
            return action.doInConnection(connection);
        } catch(SQLException e) { throw new AssertionError(e); }
    }
    @Override public void query(String sql,RowCallbackHandler handler) { query(sql,handler,new Object[0]); }
    @Override public <T> List<T> query(String sql,RowMapper<T> mapper) { return query(sql,mapper,new Object[0]); }
    @Override public void query(String sql,RowCallbackHandler handler,Object... args) {
        for(Object[] row:rows(sql,args)) try {handler.processRow(result(row));} catch(SQLException e) {throw new AssertionError(e);}
    }
    @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
        List<T> result=new ArrayList<>(); int n=0;
        for(Object[] row:rows(sql,args)) try {result.add(mapper.mapRow(result(row),n++));} catch(SQLException e) {throw new AssertionError(e);}
        return result;
    }
    private List<Object[]> rows(String sql,Object[] args) {
        reads.add(sql);
        if(!sql.startsWith("SELECT")) throw new AssertionError("Only read queries allowed");
        if(sql.startsWith("SELECT s.source_id")) return one("OWN_SOURCE","OLD_CONTRACT","OLD_DECISION",7L,"SUCCEEDED",null);
        if(sql.startsWith("SELECT a.attempt_id,a.fencing_token")) return one("OWN_ATTEMPT",1L,id,bundle.method(),1,1,0,
                resultSha,"LOW_CONFIDENCE","OWN_RULE","OWN_REASON","SUCCEEDED","EXACT",
                legacy ? "RESULT_DIGEST_DECIMAL_12/v2" : ProductPatentFieldResultEvidence.DIGEST_VERSION);
        if(sql.contains("FROM con4_result_member")) return one(member.memberId(),0L,member.sourceId(),member.documentId(),
                member.documentVersion(),member.sourceVersion(),rawSha,locator,"CLAIMS","[\"own\"]",1.0,"{\"CLAIMS\":1.0}",
                1,1.0/61,1,1.0/61,null,null);
        if(sql.contains("FROM con4_result_field_evidence")) {
            String json=codec.json(ProductPatentFieldResultEvidence.canonical(facts));
            return one("OWN_MEMBER",ProductPatentFieldResultEvidence.VERSION,json,codec.digest(ProductPatentFieldResultEvidence.VERSION,json));
        }
        if(sql.startsWith("SELECT a.attempt_id,a.scope_sha")) return one("OWN_ATTEMPT","OWN_SCOPE",bundle.method(),bundle.ordering(),
                bundle.rerankModel(),bundle.rerankStatus(),0,resultSha,ProductPatentFieldResultEvidence.DIGEST_VERSION);
        if(sql.startsWith("SELECT attempt_id,digest_version")) return one("OWN_ATTEMPT",ProductPatentFieldResultEvidence.DIGEST_VERSION,
                "SEARCH_FIELD_SCOPE/v2","[\"CLAIMS\"]",codec.json(bundle.models()),"OWN_SCOPE");
        if(sql.startsWith("SELECT created_at")) return one(Timestamp.from(executed));
        if(sql.startsWith("SELECT build_sha")) {
            if(!Arrays.equals(args,new Object[]{"OWN_SOURCE","OLD_CONTRACT","OLD_DECISION",7L,"CLAIMS","own-model@r1/MEAN_POOL_L2/v1/ALL_TOKEN_WINDOWS_WEIGHTED_MEAN_L2/v1/W8/D2"}))
                throw new AssertionError("Must use exact historical tuple");
            return wrongTuple ? List.of() : builds.stream().map(b->new Object[]{b.sha(),b.count(),Timestamp.from(b.completed())}).toList();
        }
        if(sql.startsWith("SELECT source_version")) {
            if(!Arrays.equals(args,new Object[]{"OWN_SOURCE","OLD_CONTRACT"})) throw new AssertionError("No current version substitution");
            return one("OLD_SOURCE_VERSION");
        }
        if(sql.startsWith("SELECT provider,model_id")) return one("OWN_PROVIDER","own-model","r1",
                "a".repeat(64),"b".repeat(64),2,8,"MEAN_POOL_L2/v1","OWN_ENGINEERING_LICENSE");
        if(sql.startsWith("SELECT b.document_id")) return one("OWN_DOC","OLD_DOC_VERSION",vectorSha,badVector ? new byte[]{0}:vector);
        if(sql.startsWith("SELECT selection_id")) return one("OWN_SELECTION");
        if(sql.startsWith("SELECT turn_id")) return one("OWN_TURN");
        if(sql.equals("SELECT result_set_id FROM con4_result_set")) return one(id);
        if(sql.startsWith("SELECT a.original_query")) return List.of();
        throw new AssertionError("Unmodeled query: "+sql);
    }
    private static List<Object[]> one(Object... cells) {return Collections.singletonList(cells);}
    private ResultSet result(Object[] cells) {
        boolean[] lastNull={false};
        return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(p,m,a)-> {
            if(m.getName().equals("close")) return null;
            if(m.getName().equals("wasNull")) return lastNull[0];
            Object value=cells[(Integer)a[0]-1]; lastNull[0]=value==null;
            return switch(m.getName()) {
                case "getString"->value==null ? null:value.toString();
                case "getInt"->value==null ? 0:((Number)value).intValue();case "getLong"->((Number)value).longValue();
                case "getDouble"->value==null ? 0.0:((Number)value).doubleValue();
                case "getTimestamp","getBytes"->value;default->throw new AssertionError(m);
            };
        });
    }
    @Override public int update(String sql,Object... args) {writes++;throw new AssertionError("No history writes");}
}




