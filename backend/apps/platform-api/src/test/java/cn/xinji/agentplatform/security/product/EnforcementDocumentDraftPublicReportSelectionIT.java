package cn.xinji.agentplatform.security.product;
import java.util.*;
import org.junit.jupiter.api.Test;
/** Exact upstream object selection for the admitted public case, not final-product verification. */
class EnforcementDocumentDraftPublicReportSelectionIT {
 @Test void selectExistingPublishedUpstreamRulesWithoutInventingCaseOrBasis()throws Exception {
  var s=new EnforcementDocumentDraftPublicAdmissionIT();s.initialize();
  var evidence=s.db.queryForList("SELECT rule_id,revision,case_type,basis_ref,state FROM enf4_evidence_rule r WHERE organization_id=? AND state='ACTIVE' AND revision=(SELECT MAX(x.revision) FROM enf4_evidence_rule x WHERE x.rule_id=r.rule_id) AND NOT EXISTS(SELECT 1 FROM enf4_evidence_disabled d WHERE d.rule_id=r.rule_id AND d.revision=r.revision)",s.org);
  var conduct=s.db.query("SELECT payload FROM enf7_conduct_rule r WHERE organization_id=? AND state='ACTIVE' AND revision=(SELECT MAX(x.revision) FROM enf7_conduct_rule x WHERE x.rule_id=r.rule_id) AND NOT EXISTS(SELECT 1 FROM enf7_conduct_disabled d WHERE d.rule_id=r.rule_id AND d.revision=r.revision)",(rs,n)->{try{return s.json.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},s.org);
  var refs=s.db.query("SELECT payload FROM enf9_legal_reference r WHERE organization_id=? AND revision=(SELECT MAX(x.revision) FROM enf9_legal_reference x WHERE x.reference_id=r.reference_id)",(rs,n)->{try{return s.json.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},s.org);
  s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("report-object-selection.json").toFile(),Map.of("run",s.run,"scope","READ_ONLY_UPSTREAM_OBJECT_SELECTION_NOT_DOMAIN_I","evidenceRules",evidence,"conductRules",conduct,"references",refs));
 }
}
