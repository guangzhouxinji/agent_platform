package cn.xinji.agentplatform.security.product;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real signed request and two SQL stores; controlled files, not deployment/source licensing evidence. */
final class ProductHumanWaitingRecoveryFixture {
    final ProductHumanRequestApplicationFixture request;
    final ProductRetentionPolicy taskPolicy;
    final ProductRetentionPolicy factsPolicy;
    ProductHumanWaitingRecoveryFixture() { this(new ProductHumanRequestApplicationFixture()); }
    ProductHumanWaitingRecoveryFixture(ProductHumanRequestApplicationFixture request) {
        this.request=request;this.taskPolicy=request.taskPolicy;this.factsPolicy=request.factsPolicy;
    }
    void setup(Path root) throws Exception {
        Files.createDirectory(root.resolve("capture"));
        var mapper=request.mapper;
        byte[] raw=mapper.writeValueAsBytes(Map.of("title","controlled original title"));
        Files.write(root.resolve("capture/raw.json"),raw);
        byte[] records=mapper.writeValueAsBytes(Map.of("datasetId","component-capture","generatedAt","2026-10-13",
                "records",List.of(Map.of("sourceRecordId","document-1","sourceVersion","document-v1",
                        "detailRawFile","raw.json","title","controlled original title"))));
        Files.write(root.resolve("capture/records.json"),records);
        byte[] manifest=mapper.writeValueAsBytes(Map.of("datasetId","component-capture","generatedAt","2026-10-13",
                "recordsFile","records.json","recordCount",1,"rawResponses",List.of(Map.of("type","DETAIL","file","raw.json","sha256",ProductCapturedArchive.digest(raw)))));
        Files.write(root.resolve("capture/manifest.json"),manifest);
        request.setup(root,true,"component-capture:2026-10-13",ProductCapturedArchive.digest(manifest),ProductCapturedArchive.digest(records));
        ProductHumanTaskFactsFixture.migrate(request.base.source,"dm8/V072__product_search_metadata_filters.sql");
        request.base.db.update("UPDATE con3_search_input SET filters_json=?",
                new ProductComparisonJson(mapper).canonical(ProductPatentSearchFilters.none().evidence()));
    }
    ProductHumanRequestApplication.Result packet(boolean material) {
        if(!material) return request.request();
        return request.application.request(request.base.token,request.base.task.id(),"material-request",
                new ProductHumanRequestApplication.Command(7,null,List.of(new ProductHumanMaterialReferences.Selection(
                        "source-1","document-1","document-v1",Set.of("TITLE"))),"Clarify this document","ORDINARY_ASSISTANCE"));
    }
    Instant until() { return request.base.clock.instant().plusSeconds(30); }
    ProductHumanWaitingRecoveryVerifier verifier() { return verifier(request.base.db); }
    ProductHumanWaitingRecoveryVerifier verifier(JdbcTemplate main) {
        return new ProductHumanWaitingRecoveryVerifier(main,request.base.authority,request.mapper,request.base.clock,
                request.policy,taskPolicy,factsPolicy,request.files);
    }
}
