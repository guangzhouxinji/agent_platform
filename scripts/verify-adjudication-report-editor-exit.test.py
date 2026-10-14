import importlib.util,json,shutil,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('audit',ROOT/'scripts/audit-adjudication-product-exit.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class ReportEditorExit(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(prefix='adj-editor-exit-');self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)/'evidence'
  shutil.copytree(ROOT/'docs/evidence/adjudication-workspace-20261002/report-editor-first',self.root)
 def change(self,name,modify):
  p=self.root/name;d=json.loads(p.read_text(encoding='utf-8'));modify(d);p.write_text(json.dumps(d),encoding='utf-8')
 def test_actual_editor_lifecycle_evidence_passes(self):self.assertTrue(audit.audit(self.root,True)['passed'])
 def test_missing_editor_refusal_evidence_rejected(self):
  self.change('http-records.json',lambda rows: rows.__setitem__(slice(None),[x for x in rows if not x['path'].endswith('/reports/4/revisions')]))
  with self.assertRaises(AssertionError):audit.audit(self.root,True)
 def test_editor_purge_flag_cannot_be_false(self):
  self.change('browser-result.json',lambda d:d.__setitem__('editorRevocationPurgesDraft',False))
  with self.assertRaises(AssertionError):audit.audit(self.root,True)
 def test_original_task_evidence_cannot_substitute_editor_verification(self):
  with self.assertRaises(KeyError):audit.audit(ROOT/'docs/evidence/adjudication-workspace-20261002/formal-product-first',True)
if __name__=='__main__':unittest.main(verbosity=2)
