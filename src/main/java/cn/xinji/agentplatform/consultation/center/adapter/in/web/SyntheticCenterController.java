package cn.xinji.agentplatform.consultation.center.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic.SyntheticPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentDocument;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.synthetic.SyntheticTrademarkCatalogAdapter;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkDocument;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Development-only facade for the existing synthetic catalogs. It never represents a center system. */
@RestController
@RequestMapping("/api/v0/synthetic-center")
public class SyntheticCenterController {
    private static final String MODE = "SYNTHETIC_V0";
    private static final String LIMITATION = "开发期虚构数据；不代表真实中心、全量检索、Recall@K 或专业结论。";
    private final SyntheticPatentCatalogAdapter patents;
    private final SyntheticTrademarkCatalogAdapter trademarks;
    private final SyntheticEnforcementSourceAdapter enforcement;
    private final SyntheticAdjudicationSourceAdapter adjudication;

    public SyntheticCenterController(SyntheticPatentCatalogAdapter patents, SyntheticTrademarkCatalogAdapter trademarks,
                                     SyntheticEnforcementSourceAdapter enforcement, SyntheticAdjudicationSourceAdapter adjudication) {
        this.patents = patents;
        this.trademarks = trademarks;
        this.enforcement = enforcement;
        this.adjudication = adjudication;
    }

    @GetMapping
    public Metadata metadata() {
        return new Metadata(MODE, LIMITATION, "synthetic-patent-catalog-v2", "synthetic-trademark-catalog-v2");
    }

    @GetMapping("/patents")
    public CatalogResponse<PatentDocument> patents(@RequestParam(required = false) String query,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String region) {
        List<PatentDocument> values = patents.documents().stream()
                .filter(value -> blank(type) || value.patentType().equals(type))
                .filter(value -> blank(region) || value.region().equals(region))
                .filter(value -> patentMatches(value, query))
                .sorted(Comparator.comparing(PatentDocument::sourceId))
                .toList();
        return new CatalogResponse<>(MODE, "synthetic-patent-catalog-v2", LIMITATION, values);
    }

    @GetMapping("/patents/{sourceId}")
    public ItemResponse<PatentDocument> patent(@PathVariable String sourceId) {
        PatentDocument item = patents.documents().stream().filter(value -> value.sourceId().equals(sourceId)).findFirst()
                .orElseThrow(NotFoundException::new);
        return new ItemResponse<>(MODE, "synthetic-patent-catalog-v2", LIMITATION, item);
    }

    @GetMapping("/trademarks")
    public CatalogResponse<TrademarkDocument> trademarks(@RequestParam(required = false) String query,
                                                          @RequestParam(required = false) String category,
                                                          @RequestParam(required = false) String status) {
        List<TrademarkDocument> values = trademarks.documents().stream()
                .filter(value -> blank(category) || value.categories().contains(category))
                .filter(value -> blank(status) || value.status().equals(status))
                .filter(value -> trademarkMatches(value, query))
                .sorted(Comparator.comparing(TrademarkDocument::sourceId))
                .toList();
        return new CatalogResponse<>(MODE, "synthetic-trademark-catalog-v2", LIMITATION, values);
    }

    @GetMapping("/trademarks/{sourceId}")
    public ItemResponse<TrademarkDocument> trademark(@PathVariable String sourceId) {
        TrademarkDocument item = trademarks.documents().stream().filter(value -> value.sourceId().equals(sourceId)).findFirst()
                .orElseThrow(NotFoundException::new);
        return new ItemResponse<>(MODE, "synthetic-trademark-catalog-v2", LIMITATION, item);
    }

    @GetMapping("/enforcement/clues")
    public CatalogResponse<EnforcementSourcePort.ClueData> clues() { return catalog("synthetic-enforcement-source-v2", enforcement.clues()); }

    @GetMapping("/enforcement/clues/{sourceId}")
    public ItemResponse<EnforcementSourcePort.ClueData> clue(@PathVariable String sourceId) {
        return item("synthetic-enforcement-source-v2", enforcement.clues().stream().filter(value -> value.source().sourceId().equals(sourceId)).findFirst());
    }

    @GetMapping("/enforcement/evidence")
    public CatalogResponse<EnforcementSourcePort.EvidenceData> evidence() { return catalog("synthetic-enforcement-source-v2", enforcement.evidence()); }

    @GetMapping("/enforcement/evidence/{sourceId}")
    public ItemResponse<EnforcementSourcePort.EvidenceData> evidence(@PathVariable String sourceId) {
        return item("synthetic-enforcement-source-v2", enforcement.evidence().stream().filter(value -> value.source().sourceId().equals(sourceId)).findFirst());
    }

    @GetMapping("/adjudication/cases")
    public CatalogResponse<Source> cases() { return catalog("synthetic-adjudication-source-v2", adjudication.cases()); }

    @GetMapping("/adjudication/cases/{sourceId}")
    public ItemResponse<Source> adjudicationCase(@PathVariable String sourceId) {
        return item("synthetic-adjudication-source-v2", adjudication.cases().stream().filter(value -> value.id().equals(sourceId)).findFirst());
    }

    @GetMapping("/adjudication/materials")
    public CatalogResponse<SyntheticAdjudicationSourceAdapter.MaterialCatalogItem> materials() { return catalog("synthetic-adjudication-source-v2", adjudication.materialItems()); }

    @GetMapping("/adjudication/materials/{sourceId}")
    public ItemResponse<SyntheticAdjudicationSourceAdapter.MaterialCatalogItem> material(@PathVariable String sourceId) {
        return item("synthetic-adjudication-source-v2", adjudication.materialItem(sourceId));
    }

    @GetMapping("/adjudication/legal-candidates")
    public CatalogResponse<AdjudicationApplicationService.LegalData> legalCandidates() { return catalog("synthetic-adjudication-source-v2", adjudication.legalCandidates()); }

    @GetMapping("/adjudication/template")
    public ItemResponse<Source> template() { return new ItemResponse<>(MODE, "synthetic-adjudication-source-v2", LIMITATION, adjudication.template()); }

    private static boolean patentMatches(PatentDocument value, String query) {
        if (blank(query)) return true;
        String needle = query.toLowerCase(Locale.ROOT);
        return (value.title() + " " + value.abstractText() + " " + value.claimsText() + " " + String.join(" ", value.featureTags()))
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean trademarkMatches(TrademarkDocument value, String query) {
        return blank(query) || (value.name() + " " + value.phonetic()).toLowerCase(Locale.ROOT)
                .contains(query.toLowerCase(Locale.ROOT));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static <T> CatalogResponse<T> catalog(String version, List<T> items) { return new CatalogResponse<>(MODE, version, LIMITATION, items); }
    private static <T> ItemResponse<T> item(String version, java.util.Optional<T> item) { return new ItemResponse<>(MODE, version, LIMITATION, item.orElseThrow(NotFoundException::new)); }

    public record Metadata(String dataMode, String limitation, String patentCatalogVersion, String trademarkCatalogVersion) {}
    public record CatalogResponse<T>(String dataMode, String catalogVersion, String limitation, List<T> items) {}
    public record ItemResponse<T>(String dataMode, String catalogVersion, String limitation, T item) {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static final class NotFoundException extends RuntimeException {}
}
