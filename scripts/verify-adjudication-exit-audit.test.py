"""Exit checker counterexamples; copies of evidence are never production records."""
import importlib.util,json,shutil,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('exit_audit',ROOT/'scripts/audit-adjudication-product-exit.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class ExitAuditCounterexamples(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='adj-exit-review-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'evidence'
        shutil.copytree(ROOT/'docs/evidence/adjudication-workspace-20261002/formal-product-first',self.root)
    def change(self,name,mutate):
        p=self.root/name;d=json.loads(p.read_text(encoding='utf-8-sig'));mutate(d);p.write_text(json.dumps(d),encoding='utf-8')
    def test_actual_evidence_passes(self):self.assertTrue(audit.audit(self.root)['passed'])
    def test_missing_negative_responses_rejected(self):
        self.change('http-records.json',lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['status']<400]))
        with self.assertRaises(AssertionError):audit.audit(self.root)
    def test_partial_restart_evidence_rejected(self):
        self.change('service-restarted.json',lambda d:d.__setitem__('caseBytesUnchanged',False))
        with self.assertRaises(AssertionError):audit.audit(self.root)
    def test_missing_api_test_original_rejected(self):
        (self.root/'TEST-cn.xinji.agentplatform.adjudication.casework.CaseWorkHttpsNegativesTest.xml').unlink()
        with self.assertRaises(FileNotFoundError):audit.audit(self.root)
    def test_wrong_row_count_rejected(self):
        self.change('verification.json',lambda d:d.__setitem__('adj2_case_auditRows',21))
        with self.assertRaises(AssertionError):audit.audit(self.root)
if __name__=='__main__':unittest.main(verbosity=2)
