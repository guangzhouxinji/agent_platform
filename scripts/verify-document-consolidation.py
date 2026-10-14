"""Read-only regression audit for the 2026-10-06 documentation consolidation.

This is a migration integrity check, not business verification or a new task ledger.
It compares immutable original bytes and the canonical task/requirement tables.
"""
from __future__ import annotations
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/evidence/document-consolidation-20261006"
PLAN = "docs/08-总开发实现计划.md"
ARCHIVE = "docs/evidence/document-consolidation-20261006/historical-reference.md"
LINK = re.compile(r"(?<!!)\[[^\]\n]+\]\(([^)\n]+)\)")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def table_rows(text, start, end, column):
    text = text[text.index(start):]
    text = text[:text.index(end)]
    rows = {}
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [x.strip() for x in line.strip().strip("|").split("|")]
        if not re.fullmatch(column, cells[0]):
            continue
        if cells[0] in rows:
            raise AssertionError(f"Duplicate row: {cells[0]}")
        rows[cells[0]] = cells
    return rows


def links(text, source):
    for match in LINK.finditer(text):
        url = match.group(1).strip()
        if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", url) or url.startswith("//"):
            continue
        path, _, fragment = url.partition("#")
        if "?" in path:
            continue
        target = (ROOT / source).parent / unquote(path) if path else ROOT / source
        yield target.resolve(), fragment, url


def slug(text):
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    return "".join(c for c in text.replace("`", "").lower() if c.isalnum() or c in "_- ").replace(" ", "-")


def main():
    errors = []
    manifest = json.loads((EVIDENCE / "source-manifest.json").read_text(encoding="utf-8"))
    lifecycle = json.loads((EVIDENCE / "document-lifecycle.json").read_text(encoding="utf-8"))
    originals = {}
    data = (EVIDENCE / "original-documents.zip").read_bytes()
    if digest(data) != manifest["zipSha256"]:
        errors.append("Original ZIP digest changed")
    with zipfile.ZipFile(EVIDENCE / "original-documents.zip") as z:
        for item in manifest["sources"]:
            content = z.read(item["path"])
            if digest(content) != item["sha256"]:
                errors.append(f"Original bytes mismatch: {item['path']}")
            originals[item["path"]] = content
            if item["path"].startswith("docs/evidence/"):
                current = ROOT / item["path"]
                if not current.is_file() or digest(current.read_bytes()) != item["sha256"]:
                    errors.append(f"Historical evidence changed: {item['path']}")
    current_requirements = {
        p.relative_to(ROOT).as_posix(): digest(p.read_bytes())
        for p in (ROOT / "requirements").rglob("*") if p.is_file()
    }
    if current_requirements != manifest["requirementDigests"]:
        errors.append("Requirement files or digests changed")
    migrated = [x for x in lifecycle["files"] if x["action"] == "merged-and-remove"]
    for item in migrated:
        if (ROOT / item["originalPath"]).exists():
            errors.append(f"Redundant source still exists: {item['originalPath']}")
        if item["originalPath"] not in originals:
            errors.append(f"Deleted source has no original: {item['originalPath']}")
    before = originals[PLAN].decode("utf-8-sig")
    after = (ROOT / PLAN).read_text(encoding="utf-8-sig")
    old_tasks = table_rows(before, "#### 7.8.3", "#### 7.8.4", r"[A-Z][A-Z0-9-]+")
    tasks = table_rows(after, "#### 7.8.3", "#### 7.8.4", r"[A-Z][A-Z0-9-]+")
    for task_id, row in old_tasks.items():
        if task_id not in tasks:
            errors.append(f"Task lost: {task_id}")
            continue
        # Text and status unchanged; only evidence URL destinations may be relocated.
        def without_urls(cell):
            return re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", cell)
        if [without_urls(x) for x in row] != [without_urls(x) for x in tasks[task_id]]:
            errors.append(f"Existing task content changed beyond URL migration: {task_id}")
    if set(tasks) - set(old_tasks) != {"GOV-DOC-CONSOLIDATION-01"}:
        errors.append("Unexpected added task set")
    old_req = table_rows(before, "## 8.", "## 9.", r"(?:CON|ENF|ADJ)-\d{2}")
    req = table_rows(after, "## 8.", "## 9.", r"(?:CON|ENF|ADJ)-\d{2}")
    if old_req != req or len(req) != 41:
        errors.append("41 requirement evidence rows changed")
    primary = re.findall(r"^当前(?:唯一主|优先)任务：\s*`([^`\s]+)", after, re.M)
    if len(primary) != 1 or primary[0] not in tasks:
        errors.append("Missing or ambiguous current primary task")
    for label in [f"W{i}" for i in range(8)]:
        if not re.search(r"^\| " + label + r" ", after, re.M):
            errors.append(f"Delivery route missing: {label}")
    if "### 0.5 最终交付完成标准" not in after or "### 0.7 失败分流" not in after:
        errors.append("Delivery acceptance or continuation rules missing")
    generated = json.loads((ROOT / "docs/project-plan.json").read_text(encoding="utf-8"))
    if {t["id"] for t in generated["tasks"]} != set(tasks):
        errors.append("Generated task set differs from complete Markdown table")
    if generated["currentTaskId"] != primary[0]:
        errors.append("Generated current task is stale")
    if not generated.get("currentPhaseName") or not generated.get("baselineDate"):
        errors.append("Generated phase/baseline metadata missing")
    # Existing missing targets remain visible but are not caused by this migration.
    original_paths = {(ROOT / x).resolve() for x in originals}
    baseline_missing = set()
    for source, content in originals.items():
        if not source.endswith(".md"):
            continue
        for target, fragment, url in links(content.decode("utf-8-sig"), source):
            if target not in original_paths and not target.exists():
                baseline_missing.add(target)
    archived_text = (ROOT / ARCHIVE).read_text(encoding="utf-8")
    archive_ids = set(re.findall(r'<a id="([^"]+)"', archived_text))
    archive_ids |= {slug(m.group(1)) for m in re.finditer(r"^#{1,6} (.+)$", archived_text, re.M)}
    missing = []
    checked = 0
    for file in (ROOT / "docs").rglob("*.md"):
        source = file.relative_to(ROOT).as_posix()
        for target, fragment, url in links(file.read_text(encoding="utf-8-sig"), source):
            checked += 1
            if not target.exists():
                item = {"source": source, "target": str(target), "url": url}
                missing.append(item)
                if target not in baseline_missing:
                    errors.append(f"New broken link: {source}: {url}")
            if target == (ROOT / ARCHIVE).resolve() and fragment and unquote(fragment) not in archive_ids:
                errors.append(f"Missing archive anchor: {source}: {url}")
    print(json.dumps({
        "scope": "documentation migration only; not business or full-repository verification",
        "originalSourcesVerified": len(originals),
        "removedRedundantFiles": len(migrated),
        "preservedTasks": len(old_tasks),
        "currentTasks": len(tasks),
        "preservedRequirementRows": len(req),
        "checkedLocalLinks": checked,
        "preExistingMissingTargets": len(baseline_missing),
        "remainingMissingLinks": missing,
        "errors": errors,
    }, ensure_ascii=False, indent=2))
    return 1 if errors else 0


def self_test():
    import unittest

    class DocumentationIntegrityTests(unittest.TestCase):
        def test_table_keeps_missing_trailing_delimiter_for_audit(self):
            rows = table_rows("START\n| IMP-ONE | 已完成\nEND", "START", "END", r"IMP-[A-Z]+")
            self.assertEqual(rows["IMP-ONE"], ["IMP-ONE", "已完成"])

        def test_duplicate_task_is_rejected(self):
            with self.assertRaises(AssertionError):
                table_rows("START\n| IMP-ONE | a |\n| IMP-ONE | b |\nEND", "START", "END", r"IMP-[A-Z]+")

        def test_sections_do_not_capture_other_ledgers(self):
            rows = table_rows("START\n| CON-01 | 未验收 |\nEND\n| CON-02 | 已验收 |", "START", "END", r"CON-\d{2}")
            self.assertEqual(set(rows), {"CON-01"})

        def test_percent_encoded_paths_resolve(self):
            target, fragment, url = next(links("[old](50-Product%20Freeze%20Decision.md#one)", "docs/test.md"))
            self.assertEqual(target, (ROOT / "docs/50-Product Freeze Decision.md").resolve())
            self.assertEqual(fragment, "one")

        def test_external_links_are_not_files(self):
            self.assertEqual(list(links("[a](https://example.test/a) [b](mailto:x@example.test)", "docs/a.md")), [])

        def test_same_document_anchor(self):
            target, fragment, _ = next(links("[x](#doc-01)", ARCHIVE))
            self.assertEqual(target, (ROOT / ARCHIVE).resolve())
            self.assertEqual(fragment, "doc-01")

        def test_parent_relative_links(self):
            target, _, _ = next(links("[x](../../08-总开发实现计划.md)", ARCHIVE))
            self.assertEqual(target, (ROOT / PLAN).resolve())

        def test_heading_slug(self):
            self.assertEqual(slug("7.31 执行主线（历史）"), "731-执行主线历史")

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(DocumentationIntegrityTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())
