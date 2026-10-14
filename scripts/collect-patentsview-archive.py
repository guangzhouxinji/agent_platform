"""Reproducible real PatentsView TSV capture; never grants product source admission."""
import argparse
import csv
import datetime as dt
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

FORMAT = "PATENTSVIEW_TSV_CAPTURE/v2"
SOURCE = "PATENTSVIEW_ZENODO_2024"
TABLES = {
    "claims": ("15062183", "g_claims_1976.tsv", {"patent_id", "claim_sequence", "claim_text",
                                               "dependent", "claim_number", "exemplary"}),
    "metadata": ("15783125", "g_patent.tsv", {"patent_id", "patent_type", "patent_date",
                                            "patent_title", "wipo_kind", "num_claims", "withdrawn", "filename"}),
}


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def archive_evidence(root, evidence, kind):
    record_id, member, _ = TABLES[kind]
    record_path = evidence / f"record-{record_id}.json"
    raw_metadata = record_path.read_bytes()
    record = json.loads(raw_metadata)
    if str(record["id"]) != record_id or record["metadata"]["license"]["id"] != "cc-by-4.0":
        raise ValueError("Repository identity/license changed; review the new evidence")
    matches = [file for file in record["files"] if file["key"] == member + ".zip"]
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous published archive")
    published = matches[0]
    if not re.fullmatch(r"md5:[0-9a-f]{32}", published["checksum"]):
        raise ValueError("Unsupported public archive checksum")
    archive = root / published["key"]
    if archive.stat().st_size != published["size"]:
        raise ValueError("Full archive length mismatch")
    sha, md5 = hashlib.sha256(), hashlib.md5()
    with archive.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha.update(chunk)
            md5.update(chunk)
    if "md5:" + md5.hexdigest() != published["checksum"]:
        raise ValueError("Full archive checksum mismatch")
    return archive, {"recordId": record_id, "recordSha256": digest_bytes(raw_metadata),
                     "originalUrl": published["links"]["self"], "file": "raw/archives/" + published["key"],
                     "archiveMember": member, "bytes": published["size"],
                     "publishedChecksum": published["checksum"], "sha256": sha.hexdigest()}


class TrackedLines:
    """Keep byte offsets even when a quoted CSV record spans multiple physical lines."""
    def __init__(self, handle):
        self.handle, self.offset, self.parts = handle, 0, []

    def __iter__(self):
        return self

    def __next__(self):
        raw = self.handle.readline(8 * 1024 * 1024 + 1)
        if not raw:
            raise StopIteration
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError("TSV physical line exceeds capture budget")
        self.offset += len(raw)
        self.parts.append(raw)
        return raw.decode("utf-8", errors="strict")


def table_rows(archive, kind):
    _, member, expected = TABLES[kind]
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        if len(entries) != 1 or entries[0].filename != member or entries[0].is_dir():
            raise ValueError("Unexpected ZIP member/path")
        if entries[0].file_size > 2 * 1024 ** 3 or entries[0].flag_bits & 1:
            raise ValueError("Expanded/encrypted archive outside contract")
        with package.open(entries[0]) as handle:
            tracked = TrackedLines(handle)
            reader = csv.reader(tracked, delimiter="\t", strict=True)
            header = next(reader)
            if len(header) != len(set(header)) or set(header) != expected:
                raise ValueError("TSV columns changed")
            tracked.parts.clear()
            for values in reader:
                raw = b"".join(tracked.parts)
                tracked.parts.clear()
                if len(values) != len(header):
                    raise ValueError("Incomplete/extra TSV fields")
                row = dict(zip(header, values))
                if not re.fullmatch(r"[A-Za-z0-9]{1,64}", row["patent_id"]):
                    raise ValueError("Invalid original grant identifier")
                yield row, raw, {"archiveMember": member, "startByte": tracked.offset - len(raw),
                                 "endByte": tracked.offset, "rowSha256": digest_bytes(raw)}
            if tracked.offset != entries[0].file_size:
                raise ValueError("Incomplete TSV scan")
            # Reading to EOF also verifies the ZIP entry CRC, unlike sampling its first rows.


def collect_records(archives, limit):
    selected, raw_rows = {}, {}
    print("Scanning complete claims member/CRC", file=sys.stderr, flush=True)
    for row, raw, locator in table_rows(archives["claims"][0], "claims"):
        grant = row["patent_id"]
        if grant not in selected and len(selected) < limit:
            selected[grant] = {}
        if grant not in selected:
            continue
        sequence = row["claim_sequence"]
        if not sequence.isdecimal() or not row["claim_number"].isdecimal() or not row["claim_text"].strip():
            raise ValueError("Selected claim identity/content is invalid")
        sequence = int(sequence)
        if sequence in selected[grant]:
            raise ValueError("Duplicate selected claim sequence")
        path = f"raw/rows/claims-{grant}-{sequence}.tsv"
        raw_rows[path] = raw
        selected[grant][sequence] = {"originalFields": row, "sourceLocator": dict(locator, file=path,
                                    archiveSha256=archives["claims"][1]["sha256"])}
    if len(selected) != limit:
        raise ValueError("Not enough distinct real grant IDs")
    metadata = {}
    print("Scanning complete metadata member/CRC", file=sys.stderr, flush=True)
    for row, raw, locator in table_rows(archives["metadata"][0], "metadata"):
        grant = row["patent_id"]
        if grant not in selected:
            continue
        if grant in metadata or not row["patent_title"].strip():
            raise ValueError("Duplicate or missing selected title")
        dt.date.fromisoformat(row["patent_date"])
        if not row["num_claims"].isdecimal() or int(row["num_claims"]) != len(selected[grant]):
            raise ValueError("Selected grant claims incomplete against published metadata")
        path = f"raw/rows/metadata-{grant}.tsv"
        raw_rows[path] = raw
        metadata[grant] = {"originalFields": row, "sourceLocator": dict(locator, file=path,
                            archiveSha256=archives["metadata"][1]["sha256"])}
    if set(metadata) != set(selected):
        raise ValueError("Selected grant missing from metadata archive")
    records = {}
    for grant, claims in selected.items():
        ordered = [claims[key] for key in sorted(claims)]
        record = {"representation": "DETERMINISTIC_REAL_TSV_ENVELOPE/v1", "sourceId": SOURCE,
                  "originalGrantId": grant, "title": metadata[grant]["originalFields"]["patent_title"],
                  "claimsText": "\n".join(item["originalFields"]["claim_text"] for item in ordered),
                  "metadata": metadata[grant], "claims": ordered}
        records[grant] = encoded(record)
    return records, raw_rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archives", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=20)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.limit <= 100:
        raise ValueError("Invalid bounded sample size")
    csv.field_size_limit(8 * 1024 * 1024)
    archives = {kind: archive_evidence(args.archives, args.evidence, kind) for kind in TABLES}
    records, rows = collect_records(archives, args.limit)
    source_version = ":".join(archives[kind][1]["recordSha256"] for kind in TABLES)
    entries = [{"documentId": "US_GRANT:" + grant, "originalGrantId": grant,
                "documentVersion": "RAW_SHA256:" + digest_bytes(raw), "file": f"records/{grant}.json",
                "sha256": digest_bytes(raw), "bytes": len(raw), "fields": ["TITLE", "CLAIMS"]}
               for grant, raw in records.items()]
    artifacts = dict(rows)
    artifacts.update({f"records/{grant}.json": raw for grant, raw in records.items()})
    for kind, (record_id, _, _) in TABLES.items():
        artifacts[f"raw/source-records/record-{record_id}.json"] = (args.evidence / f"record-{record_id}.json").read_bytes()
    artifacts["raw/source-records/cc-by-4-legalcode.html"] = (args.evidence / "cc-by-4-legalcode.html").read_bytes()
    archive_artifacts = [{"file": value[1]["file"], "sha256": value[1]["sha256"], "bytes": value[1]["bytes"]}
                         for value in archives.values()]
    manifest = {"schemaVersion": FORMAT, "sourceId": SOURCE, "sourceVersion": source_version,
                "status": "REAL_CANDIDATE", "productEnabled": False,
                "rightsEvidenceStatus": "REPOSITORY_CC_BY_4_0_ASSERTION",
                "originalSource": "Zenodo mirror of PatentsView release attributed to USPTO; uploader not authenticated as USPTO",
                "license": {"id": "CC-BY-4.0", "url": "https://creativecommons.org/licenses/by/4.0/",
                            "attribution": "United States Patent and Trademark Office / PatentsView; Zenodo records 15783125 and 15062183",
                            "changes": "Selected first grant IDs from full claims TSV; joined exact claim text by published sequence; deterministic per-document JSON envelope"},
                "sourceWithdrawalStatus": "NOT_ASSERTED", "legalStatusInference": False,
                "sampling": {"method": "FIRST_DISTINCT_GRANT_IDS_IN_FULL_CLAIMS_TSV", "limit": args.limit,
                             "representative": False, "professionalQrels": False, "queryResponse": False},
                "archives": {kind: value[1] for kind, value in archives.items()},
                "records": entries,
                "artifacts": sorted([{"file": path, "sha256": digest_bytes(raw), "bytes": len(raw)}
                                     for path, raw in artifacts.items()] + archive_artifacts,
                                    key=lambda item: item["file"])}
    if args.verify_only:
        saved = json.loads((args.output / "manifest.json").read_bytes())
        observed_time = saved.pop("collectedAt")
        if dt.datetime.fromisoformat(observed_time.replace("Z", "+00:00")).tzinfo is None or saved != manifest:
            raise ValueError("Capture manifest/edition drift")
        for path, raw in artifacts.items():
            if (args.output / path).read_bytes() != raw:
                raise ValueError("Capture original/derived artifact drift: " + path)
        for kind, (_, declaration) in archives.items():
            restored, observed = archive_evidence(args.output / "raw/archives", args.output / "raw/source-records", kind)
            if observed != declaration:
                raise ValueError("Portable archive proof drift")
        expected_paths = {"manifest.json"} | set(artifacts) | {item["file"] for item in archive_artifacts}
        actual_paths = {path.relative_to(args.output).as_posix() for path in args.output.rglob("*") if path.is_file()}
        if actual_paths != expected_paths:
            raise ValueError("Unexpected or missing capture files")
    else:
        args.output.mkdir(parents=True, exist_ok=False)
        for path, raw in artifacts.items():
            target = args.output / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        for archive, declaration in archives.values():
            target = args.output / declaration["file"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(archive, target)
        manifest["collectedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        (args.output / "manifest.json").write_bytes(encoded(manifest))
    print(json.dumps({"verified": args.verify_only, "records": len(records), "rawRows": len(rows),
                      "status": "REAL_CANDIDATE", "productEnabled": False, "fullArchiveCrcVerified": True,
                      "portableArchiveProof": True}))


if __name__ == "__main__":
    main()
