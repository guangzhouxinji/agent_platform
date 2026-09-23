package cn.xinji.agentplatform.consultation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class Dm8ConsultationPatentSearchAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void queriesCurrentDatabaseSnapshotAndChecksExactSourceVersion() throws Exception {
        var record = mapper.readTree("""
                {"sourceRecordId":"DB-1","sourceVersion":"v1","title":"柔性电极涂布设备",
                 "applicationNumber":"CN-A1","publicationNumber":"CN-P1","abstract":"水性粘结剂双区烘干",
                 "claims":"在线测厚反馈","ipc":["B05C9/00"],"cpc":[],"applicants":["新技术公司"],"inventors":["李工"]}
                """);
        var adapter = new Dm8ConsultationPatentSearchAdapter(() -> List.of(record));

        var result = adapter.search("水性粘结剂 在线测厚");

        assertEquals(PatentSearchPort.SearchResult.Type.SUCCESS, result.type());
        assertTrue(result.candidates().getFirst().location().contains("摘要"));
        assertTrue(result.candidates().getFirst().location().contains("权利要求"));
        assertTrue(result.actualScope().contains("DM8 KB_RECORD_VERSION / 1 件专利"));
        assertTrue(adapter.isSourceAvailable("DB-1", "v1"));
        assertFalse(adapter.isSourceAvailable("DB-1", "v2"));
    }

    @Test
    void distinguishesNoResultFromDatabaseFailureWithoutFallback() throws Exception {
        var record = mapper.readTree("""
                {"sourceRecordId":"DB-1","sourceVersion":"v1","title":"涂布设备","publicationNumber":"CN-P1"}
                """);
        var noResult = new Dm8ConsultationPatentSearchAdapter(() -> List.of(record)).search("不存在的量子装置");
        var failure = new Dm8ConsultationPatentSearchAdapter(() -> { throw new IllegalStateException("database down"); })
                .search("涂布设备");

        assertEquals(PatentSearchPort.SearchResult.Type.NO_RESULT, noResult.type());
        assertEquals(PatentSearchPort.SearchResult.Type.TOOL_FAILURE, failure.type());
        assertTrue(failure.candidates().isEmpty());
        assertTrue(failure.limitation().contains("未回退到合成数据或模拟中心"));
    }
}
