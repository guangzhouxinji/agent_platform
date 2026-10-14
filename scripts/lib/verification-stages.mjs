/** Defer only the explicitly identified test-evidence stage; all baseline/structural failures remain fatal. */
export function runVerificationStages(stages, run, report = () => {}) {
  const deferred = [];
  for (const stage of stages) {
    const status = run(stage.args);
    if (status === 0) continue;
    if (stage.deferTestHealth === true && status === 2) {
      deferred.push(stage.args.join(" "));
      report(`Deferred test evidence failure; continuing independent checks: ${stage.args.join(" ")}`);
      continue;
    }
    report(`Verification stopped: ${stage.args.join(" ")} (exit ${status ?? "unavailable"})`);
    return { exitCode: 1, deferred, fatal: stage.args.join(" ") };
  }
  if (deferred.length) report(`Independent checks finished; ${deferred.length} test evidence stage(s) remain failed. Overall result is NOT green.`);
  return { exitCode: deferred.length ? 1 : 0, deferred, fatal: null };
}

export function planHealthExitCode(issues) {
  const errors = issues.filter(issue => issue.severity === "error");
  if (!errors.length) return 0;
  return errors.every(issue => ["TESTS_FAILING", "TEST_EVIDENCE_STALE"].includes(issue.code)) ? 2 : 1;
}

/** Only a completed verification with exclusively deferred health evidence gets protocol exit 2. */
export function verificationExitCode(result, testHealthProtocol = false) {
  if (result.exitCode === 0) return 0;
  return testHealthProtocol && result.fatal === null && result.deferred.length > 0 ? 2 : 1;
}

/** Same classification protocol, extended across the existing independent quality commands. */
export function runQualityStages({ preflight, stages, postflight, run, captureSource, checkEvidence, report = () => {} }) {
  const results = [], failures = [];
  const record = (stage, status, detail = null) => {
    const entry = { stage, status, detail }; results.push(entry);
    if (status !== 0) failures.push(entry);
    report(entry); return status;
  };
  const invoke = stage => {
    try {
      const child = run(stage);
      const valid = child && !child.error && !child.signal && Number.isInteger(child.status) && child.status >= 0;
      return record(stage.label, valid ? child.status : 1, valid ? null : 'PROCESS_ERROR_SIGNAL_OR_MISSING_STATUS');
    } catch { return record(stage.label, 1, 'PROCESS_EXECUTION_THREW'); }
  };
  let before;
  try { before = captureSource(); if (typeof before !== 'string' || !before) throw new Error(); }
  catch { record('source-before', 1, 'SOURCE_BINDING_UNAVAILABLE'); return { exitCode: 1, results, failures, fatal: 'source-before' }; }
  const initial = invoke(preflight);
  if (initial !== 0 && initial !== 2) return { exitCode: 1, results, failures, fatal: preflight.label };
  for (const stage of stages) invoke(stage);
  invoke(postflight);
  try { record('fresh-test-evidence', checkEvidence() === true ? 0 : 1, 'FRESH_REPORT_CHECK'); }
  catch { record('fresh-test-evidence', 1, 'MISSING_STALE_OR_INVALID_TEST_REPORTS'); }
  try { record('source-after', captureSource() === before ? 0 : 1, 'SOURCE_SET_AND_BYTES_BINDING'); }
  catch { record('source-after', 1, 'SOURCE_BINDING_UNAVAILABLE'); }
  return { exitCode: failures.length ? 1 : 0, results, failures, fatal: null };
}