import { openSync, writeFileSync, closeSync, lstatSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { evaluateConsultationSuite } from './lib/consultation-task-quality.mjs';
import { buildConsultationReviewPacket } from './lib/consultation-professional-review.mjs';

const MANIFEST = 'governance/evaluations/consultation-task-quality-v1.json';

// Reports are new files only. Neither retained receipts nor requirement bytes are modified.
function createReport(root, relative, report) {
  if (typeof relative !== 'string' || !relative.startsWith('docs/evidence/') ||
      !relative.endsWith('.json') || relative.includes('\\') || relative.includes('\0') || relative.includes(':') ||
      path.isAbsolute(relative) || relative.split('/').some(p => !p || p === '.' || p === '..' || /[. ]$/.test(p))) {
    throw Error('UNSAFE_REPORT_PATH');
  }
  const base = realpathSync(root);
  let parent = base;
  const parts = relative.split('/');
  for (const part of parts.slice(0, -1)) {
    parent = path.join(parent, part);
    const stat = lstatSync(parent);
    if (stat.isSymbolicLink() || !stat.isDirectory()) throw Error('UNSAFE_REPORT_PARENT');
  }
  const resolvedParent = realpathSync(parent);
  const parentRelative = path.relative(base, resolvedParent);
  if (parentRelative.startsWith('..') || path.isAbsolute(parentRelative)) throw Error('REPORT_OUTSIDE_WORKSPACE');
  const target = path.join(resolvedParent, parts.at(-1));
  const fd = openSync(target, 'wx', 0o600);
  try { writeFileSync(fd, JSON.stringify(report, null, 2) + '\n', 'utf8'); }
  finally { closeSync(fd); }
}

export function main(root, args, output = console.log, error = console.error) {
  try {
    let verify = false, professional = false, reportPath, reviewPath;
    const seen = new Set();
    for (let i = 0; i < args.length; i++) {
      const arg = args[i];
      if (seen.has(arg)) throw Error('DUPLICATE_ARGUMENT');
      seen.add(arg);
      if (arg === '--verify') verify = true;
      else if (arg === '--require-professional') professional = true;
      else if (arg === '--report') {
        reportPath = args[++i];
        if (!reportPath || reportPath.startsWith('--')) throw Error('REPORT_PATH_REQUIRED');
      } else if (arg === '--review-packet') {
        reviewPath = args[++i];
        if (!reviewPath || reviewPath.startsWith('--')) throw Error('REVIEW_PATH_REQUIRED');
      } else throw Error('UNKNOWN_ARGUMENT');
    }
    if (verify && (reportPath || reviewPath)) throw Error('VERIFY_IS_READ_ONLY');
    if (reportPath && reviewPath) throw Error('ONE_OUTPUT_MODE_REQUIRED');
    if (reviewPath) {
      const packet = buildConsultationReviewPacket(root);
      createReport(root, reviewPath, { ...packet, generatedAt: new Date().toISOString() });
      output(JSON.stringify({ schema: packet.schema, denominator: packet.denominator, professionalAccepted: false, overallAcceptance: 'INCOMPLETE', currentAuthorization: 'NOT_EVALUATED', reviewPath }));
      return professional ? 2 : 0; // Index delivery is not professional acceptance.
    }
    const report = evaluateConsultationSuite(root, MANIFEST);
    if (reportPath) createReport(root, reportPath, { ...report, generatedAt: new Date().toISOString() });
    output(JSON.stringify({
      scope: report.scope, mechanicalPassed: report.mechanicalPassed,
      mechanicalSummary: report.mechanicalSummary, professionalAccepted: report.professionalAccepted,
      overallAcceptance: report.overallAcceptance, currentAuthorization: report.currentAuthorization,
      unobservedCoverage: report.unobservedCoverage,
      cases: report.cases.map(c => ({ id: c.id, passed: c.passed, counts: c.counts, failedChecks: c.checks.filter(x => !x.passed) })),
      errors: report.errors, ...(reportPath ? { reportPath } : {})
    }, null, 2));
    if (!report.mechanicalPassed) return 1;
    if (professional && !report.professionalAccepted) return 2;
    return 0; // Only the explicitly limited mechanical contract; not full acceptance.
  } catch (cause) {
    error('Consultation quality evaluation refused: ' + cause.message);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = main(process.cwd(), process.argv.slice(2));
}
