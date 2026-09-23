package cn.xinji.mockcenter.application;

import java.util.List;

/** Development data administration is deliberately separate from the future read-only center contract. */
public interface MockCenterPatentCatalogImporter {
    void importBatch(ImportBatch batch, List<MockCenterPatentCatalog.PatentRecord> records);

    record ImportBatch(String batchId, String datasetVersion, String datasetDigest, int recordCount, String importedAt) {}
}
