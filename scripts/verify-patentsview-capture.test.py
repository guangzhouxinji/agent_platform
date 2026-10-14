"""Mechanical archive/CSV contract tests; not patent data or product source admission."""
import csv
import importlib.util
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("capture", Path(__file__).with_name("collect-patentsview-archive.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class ArchiveContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="patentsview-csv-contract-")
        self.addCleanup(self.temp.cleanup)
        self.archive = Path(self.temp.name) / "mechanical.zip"

    def write_rows(self, rows, extra=False):
        text = io.StringIO(newline="")
        writer = csv.writer(text, delimiter="\t", lineterminator="\n")
        writer.writerow(["patent_id", "claim_sequence", "claim_text", "dependent", "claim_number", "exemplary"])
        writer.writerows(rows)
        raw = text.getvalue().encode("utf-8")
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("g_claims_1976.tsv", raw)
            if extra:
                archive.writestr("unexpected.txt", b"extra")
        return raw

    def test_quoted_multiline_unicode_and_original_byte_locator(self):
        raw = self.write_rows([["RE29015", "0", '第一行\t"quoted"\n第二行', "", "1", "0"],
                               ["D242832", "0", "next", "", "1", "0"]])
        rows = list(capture.table_rows(self.archive, "claims"))
        self.assertEqual(2, len(rows))
        self.assertEqual('第一行\t"quoted"\n第二行', rows[0][0]["claim_text"])
        for row, original, locator in rows:
            self.assertEqual(original, raw[locator["startByte"]:locator["endByte"]])
            self.assertEqual(capture.digest_bytes(original), locator["rowSha256"])
        self.assertEqual(rows[0][2]["endByte"], rows[1][2]["startByte"])

    def test_extra_archive_member_is_not_ignored(self):
        self.write_rows([["1", "0", "text", "", "1", "0"]], extra=True)
        with self.assertRaisesRegex(ValueError, "Unexpected ZIP"):
            list(capture.table_rows(self.archive, "claims"))

    def test_incomplete_csv_row_is_not_a_complete_claim(self):
        self.write_rows([["1", "0", "missing columns"]])
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            list(capture.table_rows(self.archive, "claims"))

    def test_unsafe_identifier_is_rejected_before_path_creation(self):
        self.write_rows([["../1", "0", "text", "", "1", "0"]])
        with self.assertRaisesRegex(ValueError, "identifier"):
            list(capture.table_rows(self.archive, "claims"))

    def test_invalid_utf8_does_not_get_replacement_characters(self):
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("g_claims_1976.tsv", b"\xff\n")
        with self.assertRaises(UnicodeDecodeError):
            list(capture.table_rows(self.archive, "claims"))

    def test_full_member_crc_must_be_read_not_just_sample_prefix(self):
        raw = self.write_rows([["1", "0", "text", "", "1", "0"]])
        package = self.archive.read_bytes()
        position = package.index(raw)
        broken = bytearray(package)
        broken[position + len(raw) - 3] ^= 1
        self.archive.write_bytes(broken)
        with self.assertRaises(zipfile.BadZipFile):
            list(capture.table_rows(self.archive, "claims"))


if __name__ == "__main__":
    unittest.main()
