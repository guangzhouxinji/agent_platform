"""Recorded public source integrity negatives; test copies never become approved sources."""
import contextlib, importlib.util, io, json, shutil, tempfile, unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('capture', ROOT/'scripts/capture-adjudication-public-law.py')
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)
class PublicSourceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='adj-public-source-test-')
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name)/'bundle'
        shutil.copytree(ROOT/'data/adjudication/public-legal-sources-v1', self.bundle)
    def verify(self):
        with contextlib.redirect_stdout(io.StringIO()): capture.verify(self.bundle)
    def mutate_json(self, name, mutate):
        path = self.bundle/name
        data = json.loads(path.read_text(encoding='utf-8'))
        mutate(data)
        path.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
    def test_recorded_real_official_original_passes(self): self.verify()
    def test_original_byte_mutation_rejected(self):
        with (self.bundle/'original.pdf').open('ab') as file: file.write(b'drift')
        with self.assertRaisesRegex(ValueError,'integrity drift'): self.verify()
    def test_text_mutation_rejected(self):
        (self.bundle/'text.txt').write_text('changed',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'integrity drift'): self.verify()
    def test_missing_artifact_rejected(self):
        (self.bundle/'original.pdf').unlink()
        with self.assertRaises(FileNotFoundError): self.verify()
    def test_manifest_page_mutation_rejected(self):
        self.mutate_json('manifest.json',lambda m:m['pages'].__setitem__(16,'changed'))
        with self.assertRaisesRegex(ValueError,'extraction drift'): self.verify()
    def test_manifest_digest_mutation_rejected(self):
        self.mutate_json('manifest.json',lambda m:m.__setitem__('purpose','altered'))
        with self.assertRaisesRegex(ValueError,'Provenance drift'): self.verify()
    def test_escaped_artifact_path_rejected(self):
        self.mutate_json('manifest.json',lambda m:m['artifacts'][0].__setitem__('path','../outside'))
        with self.assertRaisesRegex(ValueError,'escaped bundle'): self.verify()
    def test_citation_text_mutation_rejected(self):
        self.mutate_json('citation.json',lambda m:m.__setitem__('originalText','changed'))
        with self.assertRaisesRegex(ValueError,'Citation text drift'): self.verify()
    def test_capture_refuses_existing_version_without_overwrite(self):
        before = (self.bundle/'manifest.json').read_bytes()
        with self.assertRaisesRegex(ValueError,'overwrite'): capture.capture(self.bundle)
        self.assertEqual(before,(self.bundle/'manifest.json').read_bytes())
if __name__ == '__main__': unittest.main(verbosity=2)
