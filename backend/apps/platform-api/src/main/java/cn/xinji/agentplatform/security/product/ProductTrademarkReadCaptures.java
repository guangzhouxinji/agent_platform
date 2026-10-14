package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current approved bytes and one exact committed capture; never imports or authenticates a maintainer. */
final class ProductTrademarkReadCaptures {
    record Stamp(String receipt, String requestSha, String approval, Map<String,String> facts, Instant until) {
        Stamp { facts = Map.copyOf(facts); }
    }
    record Capture(ProductTrademarkProjectionStore.Selection selection, Stamp stamp,
                   ProductTrademarkCapture.VerifiedTrademarkCapture verified) { }
    private final JdbcTemplate db, authority;
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final List<ProductTrademarkProjectionApproval.Pin> pins;
    ProductTrademarkReadCaptures(JdbcTemplate db, JdbcTemplate authority, ProductSourceEvidenceFiles files,
            ObjectMapper mapper, Clock clock, List<ProductTrademarkProjectionApproval.Pin> pins) {
        this.db=db; this.authority=authority; this.files=files; this.mapper=mapper; this.clock=clock;
        this.pins=List.copyOf(pins);
        require(!pins.isEmpty() && pins.size()<=8, Code.CAPABILITY_UNAVAILABLE);
        var keys=new HashSet<String>();
        for(var pin:pins) require(keys.add(pin.sourceId()+"\n"+pin.contractVersion()),Code.CAPABILITY_UNAVAILABLE);
        require(db.getDataSource()!=authority.getDataSource(),Code.CAPABILITY_UNAVAILABLE);
    }
    Set<String> sourceIds() { var ids=new TreeSet<String>(); pins.forEach(p->ids.add(p.sourceId())); return Set.copyOf(ids); }
    Capture read(ProductTaskDirectory.Task task, ProductSourceAvailability.Active source) {
        var matching=pins.stream().filter(p->p.sourceId().equals(source.sourceId()) && p.contractVersion().equals(source.contractVersion())).toList();
        require(matching.size()==1,Code.SOURCE_UNAVAILABLE); var pin=matching.getFirst();
        var approved=new ProductTrademarkProjectionApproval(mapper,files,pin); approved.current(clock.instant());
        same(approved,"organization",task.organization()); same(approved,"taskId",task.id()); same(approved,"scopeId",task.scopeId());
        require(approved.number("taskAuthorizationVersion")==task.authorizationVersion(),Code.SOURCE_UNAVAILABLE);
        same(approved,"primaryDatabaseIdentitySHA",ProductTrademarkSourceProjectionAuthority.databaseIdentitySHA(db));
        same(approved,"authorityDatabaseIdentitySHA",ProductTrademarkSourceProjectionAuthority.databaseIdentitySHA(authority));
        require(!approved.value("primaryDatabaseIdentitySHA").equals(approved.value("authorityDatabaseIdentitySHA")),Code.SOURCE_UNAVAILABLE);
        var rows=db.query("SELECT v.verification_id,k.source_version,a.rights_ref,a.rights_sha,d.retention_ref,d.retention_version,"
                +"d.retention_evidence_sha,a.responsible_party,a.capability_ref,a.capability_sha,v.manifest_ref,v.manifest_sha,"
                +"v.records_sha,v.inventory_sha,v.record_count,v.raw_count FROM con4_source_current c "
                +"JOIN con4_source_contract k ON k.source_id=c.source_id AND k.contract_version=c.contract_version "
                +"JOIN con4_source_admission a ON a.decision_id=c.decision_id AND a.source_id=c.source_id AND a.contract_version=c.contract_version "
                +"JOIN con4_source_product_decision d ON d.decision_id=a.decision_id "
                +"JOIN con4_source_verification v ON v.source_id=c.source_id AND v.contract_version=c.contract_version AND v.source_version=k.source_version "
                +"WHERE c.source_id=? AND c.contract_version=? AND c.decision_id=? AND c.source_epoch=? AND c.row_version=? "
                +"AND c.status='ACTIVE' AND k.status='ACTIVE' AND k.row_version=2 AND v.candidate_row_version=1 "
                +"AND d.organization_id=? AND d.task_id=? AND d.scope_id=? AND d.rights_evidence_status<>'WITHDRAWN'",(rs,n)->{
                    var facts=new LinkedHashMap<String,String>(); var names=List.of("verificationId","registeredSourceVersion","rightsEvidenceRef","rightsEvidenceSHA",
                            "retentionRef","retentionVersion","retentionEvidenceSHA","responsibleParty","capabilityRef","capabilitySHA",
                            "manifestRef","manifestSHA","recordsSHA","inventorySHA","recordCount","rawCount");
                    for(int i=0;i<names.size();i++)facts.put(names.get(i),rs.getString(i+1)); return facts;
                },source.sourceId(),source.contractVersion(),source.decisionId(),source.epoch(),source.rowVersion(),task.organization(),task.id(),task.scopeId());
        require(rows.size()==1,Code.SOURCE_UNAVAILABLE); var facts=rows.getFirst();
        for(String name:List.of("verificationId","registeredSourceVersion","rightsEvidenceRef","rightsEvidenceSHA","retentionRef","retentionEvidenceSHA","responsibleParty"))
            same(approved,name,facts.get(name));
        require(approved.number("retentionVersion")==Long.parseLong(facts.get("retentionVersion"))
                && pin.capabilityRef().equals(facts.get("capabilityRef")) && pin.capabilitySHA().equals(facts.get("capabilitySHA")),Code.SOURCE_UNAVAILABLE);
        var loaded=new ProductTrademarkProjectionEvidence(files,mapper).load(approved,facts.get("manifestRef"),facts.get("manifestSHA"),
                facts.get("recordsSHA"),facts.get("inventorySHA"),Long.parseLong(facts.get("recordCount")),Long.parseLong(facts.get("rawCount")));
        var receipts=db.query("SELECT receipt_id,request_sha,expires_at FROM con1tm_capture_request WHERE organization_id=? AND task_id=? "
                +"AND authorization_version=? AND source_id=? AND contract_version=? AND decision_id=? AND source_epoch=? AND source_row_version=? "
                +"AND registered_source_version=? AND manifest_ref=? AND manifest_sha=? AND records_sha=? AND inventory_sha=? AND binding_set_sha=? "
                +"AND map_id=? AND map_version=? AND map_sha=? AND retention_ref=? AND retention_version=? AND retention_evidence_sha=? "
                +"AND approved_scope_sha=? AND approval_sha=? AND maintainer_ref=? AND certificate_sha=? AND status='COMMITTED' "
                +"ORDER BY committed_at,receipt_id FETCH FIRST 1 ROWS ONLY",(rs,n)->new Stamp(rs.getString(1),rs.getString(2),approved.fingerprint(),facts,rs.getTimestamp(3).toInstant()),
                task.organization(),task.id(),task.authorizationVersion(),source.sourceId(),source.contractVersion(),source.decisionId(),source.epoch(),source.rowVersion(),
                source.sourceVersion(),facts.get("manifestRef"),facts.get("manifestSHA"),facts.get("recordsSHA"),facts.get("inventorySHA"),approved.value("bindingSetSHA"),
                approved.value("mapId"),approved.value("mapVersion"),approved.value("mapSHA"),approved.value("retentionRef"),approved.number("retentionVersion"),
                approved.value("retentionEvidenceSHA"),pin.approvedScopeSHA(),pin.approvalEvidenceSHA(),approved.value("subject"),approved.value("certificateSHA"));
        require(receipts.size()==1,Code.SOURCE_UNAVAILABLE); Stamp stamp=receipts.getFirst();
        require(clock.instant().isBefore(stamp.until()) && !stamp.until().isAfter(approved.until("retainUntil"))
                && !stamp.until().isAfter(approved.until("recoveryUntil")) && !stamp.until().isAfter(approved.until("validUntil")),Code.SOURCE_UNAVAILABLE);
        stamp=new Stamp(stamp.receipt(),stamp.requestSha(),stamp.approval(),stamp.facts(),
                ProductTrademarkReadSources.minimum(stamp.until(),approved.until("backupUntil")));
        return new Capture(new ProductTrademarkProjectionStore.Selection(loaded.input(),stamp.receipt(),stamp.requestSha()),stamp,loaded.capture());
    }
    private static void same(ProductTrademarkProjectionApproval approval,String key,String value) {
        require(approval.value(key).equals(value),Code.SOURCE_UNAVAILABLE);
    }
}
