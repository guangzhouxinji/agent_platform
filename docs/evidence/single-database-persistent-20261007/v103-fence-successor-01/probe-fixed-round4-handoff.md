# Fixed product probe, successor round4

Actual DM8 round4: 40 mechanical assertions passed, 0 failed, Java exit0. Main-frozen ProductComparisonStartSchema ad0d7658caa0a255d42708a10bb6d5f343bd4f0af675235971d066312f838131 independently compiled and passes STARTMAIN_SVC ALL_CONSTRAINTS canonical `NOT(FENCING_TOKEN IS NULL)` with type C and STATUS ENABLED.

All19 main table row counts/sortedRowSha256 equal first main-before-v103 and original V102 persistent baseline. JSON object member order can differ; compare row digests rather than serialized JSON bytes. Original V10240 public files remain byte-identical. Original V102 failure, initial V103 dictionary mismatch and subsequent failed rounds remain preserved. Earlier README/receipts state historical PARTIAL, not current round4 result.

STARTMAIN V103 never replayed or reversed. Existing own V103NEG/V103EMPTY only reused. Prior illegal row unchanged after actual forward failure; empty fixture alone has its added CHECK reinstalled for actual U103 empty inverse retest. Main nonempty U103 refuses before DROP. All NULL/COMMITTED NULL/zero/negative counterexamples rejected, reserved NULL allowed. All transactional probes rolled back.

No shared code/SQL/scripts writes, no API/model/production data; primary5492 stopped, no A state changes or budget expansion. Private DPAPI vault/classes excluded from publication. Limited physical fix + actual runtime startup gate exit only; main owns status/regression/verify/deployment and formal acceptance remains separate.
