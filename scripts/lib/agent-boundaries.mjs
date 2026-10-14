const domains = ["consultation", "enforcement", "adjudication"];

// Module boundaries are stricter than deployments: API/Event/Contract must be extracted ports,
// not imports of a separately owned Agent's implementation packages.
export function agentBoundaryViolations(owner, source, artifacts = []) {
  const violations = [];
  const forbidden = owner === "kernel" ? domains : domains.filter(domain => domain !== owner);
  for (const domain of forbidden) {
    if (new RegExp(`\\bcn\\.xinji\\.agentplatform\\.${domain}\\b`).test(source))
      violations.push(`implementation dependency on ${domain}`);
    if (artifacts.includes(`agent-platform-${domain}`)) violations.push(`module dependency on ${domain}`);
  }
  if (owner === "kernel" && /\b(?:org\.springframework|java\.sql|javax\.sql|java\.net\.http|cn\.xinji\.agentplatform\.modelconfig)\b/.test(source))
    violations.push("kernel depends on framework, persistence, transport or model administration");
  return violations;
}
