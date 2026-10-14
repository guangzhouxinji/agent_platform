-- Do NOT run on retained evidence. This reversal permits dropping only an empty fence.
-- Intentional constraint violation aborts before DROP when any invocation exists.
INSERT INTO con3_model_invocation(invocation_id,task_id,task_version,actor_subject,kind,request_id,
    request_sha,created_at,expires_at,retention_ref,retention_version,retention_evidence_sha)
SELECT invocation_id,task_id,task_version,actor_subject,kind,request_id,
    request_sha,created_at,expires_at,retention_ref,retention_version,retention_evidence_sha
FROM con3_model_invocation;
DROP TABLE con3_model_invocation;
