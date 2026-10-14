"""Verifier counterexamples only; no product behavior fixture/acceptance substitute."""
import copy,json,pathlib,tempfile,unittest,xml.etree.ElementTree as ET
import verify_offline as v
class OfflineVerifierTest(unittest.TestCase):
 def test_actual_twenty_errors_are_bound_individually(self):
  summary=json.loads((v.REPORT/'full-backend-round1-result.json').read_text(encoding='utf-8'))
  rows=v.errors_from_reports(summary,v.REPORT)
  self.assertEqual(20,len(rows));self.assertEqual(11,len(set(r['class'] for r in rows)))
  self.assertEqual(9,len([r for r in rows if r['scenario']]))
 def test_wrong_total_is_rejected(self):
  summary=json.loads((v.REPORT/'full-backend-round1-result.json').read_text(encoding='utf-8'));summary['totals']['errors']=19
  with self.assertRaisesRegex(ValueError,'ERROR_COUNT_MISMATCH'):v.errors_from_reports(summary,v.REPORT)
 def test_changed_xml_digest_is_rejected(self):
  summary=json.loads((v.REPORT/'full-backend-round1-result.json').read_text(encoding='utf-8'))
  entry=next(x for x in summary['reports'] if x['errors']);entry['sha256']='0'*64
  with self.assertRaisesRegex(ValueError,'SHA_MISMATCH'):v.errors_from_reports(summary,v.REPORT)
 def test_missing_file_is_not_a_pass(self):
  with self.assertRaises(FileNotFoundError):v.bound(v.OUT/'never-exists.xml','0'*64)
 def test_tampered_bytes_are_rejected(self):
  with tempfile.TemporaryDirectory(dir=v.OUT) as d:
   p=pathlib.Path(d)/'bytes';p.write_bytes(b'original');digest=v.sha(p);p.write_bytes(b'tampered')
   with self.assertRaisesRegex(ValueError,'SHA_MISMATCH'):v.bound(p,digest)
 def report_counterexample(self,classname,message):
  with tempfile.TemporaryDirectory(dir=v.OUT) as d:
   p=pathlib.Path(d);tree=ET.Element('testsuite');test=ET.SubElement(tree,'testcase',classname=classname,name='x');ET.SubElement(test,'error',message=message,type='java.lang.IllegalStateException');ET.ElementTree(tree).write(p/'x.xml')
   summary={'totals':{'errors':1},'reports':[{'file':'x.xml','sha256':v.sha(p/'x.xml'),'errors':1}]}
   return v.errors_from_reports(summary,p)
 def test_unknown_error_cannot_be_classified_as_environment(self):
  with self.assertRaisesRegex(ValueError,'UNCLASSIFIED_ERROR'):self.report_counterexample('UnknownTest','KEY required')
 def test_sql_error_cannot_be_ignored_as_environment(self):
  with self.assertRaisesRegex(ValueError,'NON_ENVIRONMENT_ERROR'):self.report_counterexample('cn.xinji.agentplatform.enforcement.EnforcementFollowupDm8MigrationTest','invalid table')
 def test_non_class_rejected(self):
  with self.assertRaisesRegex(ValueError,'NOT_CLASS'):v.cp_utf8(b'not a java class')
 def test_fixed_guard_class_has_real_schema_error_prefix(self):
  import zipfile
  m=json.loads(v.MANIFEST.read_text(encoding='utf-8'))
  with zipfile.ZipFile(v.ROOT/m['artifact']['artifact']['path']) as z:
   b=z.read('BOOT-INF/classes/cn/xinji/agentplatform/security/product/ProductComparisonSchemaGuard.class')
   self.assertTrue(any('COMPARISON_SCHEMA_REQUIRED:' in x for x in v.cp_utf8(b)))
if __name__=='__main__':unittest.main(verbosity=2)
