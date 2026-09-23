package cn.xinji.mockcenter;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.mockcenter.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockCenterPublicPatentSeederTest {
    @Test void importsOnlyExistingPublicPilotRecordsWithTraceableFields() {
        CapturingCatalog catalog = new CapturingCatalog();
        new MockCenterPublicPatentSeeder(catalog, new ObjectMapper().findAndRegisterModules()).seed();
        assertNotNull(catalog.batch); assertFalse(catalog.records.isEmpty());
        assertEquals("public-pilot-v0.1", catalog.batch.datasetVersion());
        assertTrue(catalog.records.stream().allMatch(value -> value.sourceId().equals("PUBLIC_PILOT") && value.recordId().startsWith("MCP-") && !value.sourceUrl().isBlank()));
        assertTrue(catalog.records.stream().allMatch(value -> value.contentDigest().length() == 64));
    }
    private static final class CapturingCatalog implements MockCenterPatentCatalogImporter {
        ImportBatch batch; List<MockCenterPatentCatalog.PatentRecord> records=List.of();
        public void importBatch(ImportBatch batch,List<MockCenterPatentCatalog.PatentRecord> records){this.batch=batch;this.records=records;}
    }
}
