package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.mockcenter.adapter.out.dm8.Dm8MockCenterPatentCatalog;
import cn.xinji.mockcenter.application.MockCenterPatentCatalog;
import cn.xinji.mockcenter.application.MockCenterPatentCatalogImporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.*;

class Dm8MockCenterPatentCatalogIntegrationTest {
    private String url, user, password;

    @BeforeEach void configure() throws Exception {
        url=System.getenv("DM8_JDBC_URL"); user=System.getenv("DM8_JDBC_USER"); password=System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url!=null&&user!=null&&password!=null,"DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try(Connection connection=connection()){rollback(connection);run(connection,"db/dm8/V007__mock_center_public_patent_catalog.sql");}
    }

    @AfterEach void cleanup() throws Exception { if(url!=null) try(Connection connection=connection()){rollback(connection);} }

    @Test void importsSearchesPagesAndPreservesImmutableSourceVersions() throws Exception {
        Dm8MockCenterPatentCatalog catalog=new Dm8MockCenterPatentCatalog(()->{try{return connection();}catch(SQLException failure){throw new IllegalStateException(failure);}},new ObjectMapper());
        var batch=new MockCenterPatentCatalogImporter.ImportBatch("batch-a","pilot-v1","a".repeat(64),2,"2026-09-17T00:00:00Z");
        var first=record("MCP-1","SRC-1","CN-1-A","电池极片分段烘干方法","低温预烘后进行高温烘干","1".repeat(64),LocalDate.of(2026,1,2));
        var second=record("MCP-2","SRC-2","CN-2-A","半导体封装方法","芯片封装结构","2".repeat(64),LocalDate.of(2026,1,1));
        catalog.importBatch(batch,List.of(first,second));
        catalog.importBatch(batch,List.of(first,second));

        assertEquals(2,catalog.metadata().recordCount());
        assertEquals("MCP-1",catalog.search(new MockCenterPatentCatalog.Query("电池",null,1,10)).items().getFirst().recordId());
        assertEquals(1,catalog.search(new MockCenterPatentCatalog.Query(null,null,2,1)).items().size());
        assertEquals("CN-2-A",catalog.find("MCP-2").orElseThrow().publicationNumber());
        assertEquals(0,catalog.search(new MockCenterPatentCatalog.Query("%",null,1,10)).total());

        var changed=record("MCP-1","SRC-1","CN-1-A","被覆盖的标题","不同内容","3".repeat(64),LocalDate.of(2026,1,2));
        assertThrows(IllegalStateException.class,()->catalog.importBatch(batch,List.of(changed,second)));
        assertEquals("电池极片分段烘干方法",catalog.find("MCP-1").orElseThrow().title());
    }

    @Test void pagesHundredPlusRecordsWithoutDuplicatesOrOrderDrift() {
        Dm8MockCenterPatentCatalog catalog=new Dm8MockCenterPatentCatalog(()->{try{return connection();}catch(SQLException failure){throw new IllegalStateException(failure);}},new ObjectMapper());
        List<MockCenterPatentCatalog.PatentRecord> records = new ArrayList<>();
        for (int index = 0; index < 125; index++) {
            String suffix = String.format("%03d", index);
            records.add(record("MCP-L-" + suffix, "SRC-L-" + suffix, "CN-L-" + suffix + "-A",
                    "共同分页主题 " + suffix, "共同分页主题摘要", String.format("%064x", index + 1),
                    LocalDate.of(2026, 1, 1).minusDays(index / 5)));
        }
        catalog.importBatch(new MockCenterPatentCatalogImporter.ImportBatch("batch-large", "pilot-large-v1",
                "f".repeat(64), records.size(), "2026-09-17T00:00:00Z"), records);

        List<String> ids = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            MockCenterPatentCatalog.Page result = catalog.search(new MockCenterPatentCatalog.Query("共同分页主题", null, page, 50));
            assertEquals(125, result.total());
            ids.addAll(result.items().stream().map(MockCenterPatentCatalog.PatentRecord::recordId).toList());
        }
        assertEquals(125, ids.size());
        assertEquals(125, new HashSet<>(ids).size());
        assertEquals(ids.subList(0, 50), catalog.search(new MockCenterPatentCatalog.Query("共同分页主题", null, 1, 50))
                .items().stream().map(MockCenterPatentCatalog.PatentRecord::recordId).toList());
        assertEquals(0, catalog.search(new MockCenterPatentCatalog.Query("共同分页主题%", null, 1, 50)).total());
    }

    private static MockCenterPatentCatalog.PatentRecord record(String id,String sourceRecord,String publication,String title,String summary,String digest,LocalDate published){return new MockCenterPatentCatalog.PatentRecord(id,"PUBLIC_TEST",sourceRecord,"v1",publication,null,title,List.of("测试申请人"),List.of(),null,published,List.of("G06N"),summary,"PARTIAL","https://example.invalid/"+sourceRecord,"2026-09-17T00:00:00Z",digest,"mock-center-public-patent-v1");}
    private Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);}
    private static void run(Connection connection,String path)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(path)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void rollback(Connection connection)throws Exception{try{run(connection,"db/dm8/U007__mock_center_public_patent_catalog.sql");}catch(SQLException ignored){}}
}
