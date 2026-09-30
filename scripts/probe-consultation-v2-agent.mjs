const base = process.env.V2_BASE_URL;
const token = process.env.V2_AGENT_TOKEN;
const attempt = process.env.V2_ATTEMPT_ID;
const reference = process.env.V2_COMMITTED_REF;
if (!base || !token || !attempt || !reference) throw new Error("Agent probe configuration missing");

async function command(suffix, body) {
  const response = await fetch(`${base}/v2/internal/consultation/attempts/${attempt}/${suffix}`, {
    method: "POST",
    headers: { Authorization: token, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`Agent probe ${suffix} failed with HTTP ${response.status}`);
  return response.json();
}

const lease = await command("lease");
if (lease.attemptId !== attempt || !Number.isSafeInteger(lease.fencingToken)) {
  throw new Error("Agent probe received an invalid lease");
}
const checkpoint = await command("checkpoint", {
  token: lease.fencingToken,
  committedRef: reference,
});
if (checkpoint.attemptId !== attempt || checkpoint.committedRef !== reference) {
  throw new Error("Agent probe received an invalid checkpoint");
}
console.log("AGENT_PROBE_OK");
