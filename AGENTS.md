# Project Instructions

## Source Of Truth

- Read `docs/08-总开发实现计划.md` before starting design or implementation work.
- Treat `requirements/` as the read-only business source.
- Follow `docs/01-总体需求与架构方案.md` and `docs/09-开发治理与需求追踪规范.md`.
- Stop and report a baseline change when a requirement file is added, removed, or its recorded digest changes.
- Run `node scripts/verify-all.mjs` before and after changing requirement status, design evidence, unresolved decisions, trace evidence, or business implementation.
- Treat `docs/08-总开发实现计划.md` as the only task/status control record; do not create parallel Trace, Gate, Review, or open-question status ledgers.

## Final-Product Implementation

- Implement every capability against the approved final-product contract, including UI, API, data, workflow, permissions, failures, AI/human boundaries, and acceptance. A development-only, demo, fixture, synthetic, fixed-candidate, or temporary product path is not an implementation substitute.
- When real business data is unavailable, use only collected, de-identified, or explicitly approved substitute data within its recorded license and purpose. Substitute data changes the input, not the product behavior or result contract.
- Do not add development-only product pages, fields, states, hints, routes, identities, or permission bypasses. Existing development-specific behavior is historical evidence, not a product D gate or reusable product capability until reviewed and rebuilt against the final contract.
- If final identity, source rights, professional method, workflow rule, or recovery behavior is undecided, keep the dependent product branch gated; do not infer it from a test harness or a prior narrow development decision.

## Work Selection

- Work on the current primary task in the master plan unless the user explicitly changes priority.
- Do not start a task whose dependencies or stage gate are unmet.
- Assign every new task a stable task ID, dependencies, exit criteria, and status before implementation.
- Do not start business design or implementation until the requirement passes the R gate in the master plan.
- Do not write business APIs, migrations, or code until the same requirement passes the D gate.
- Do not infer unresolved legal, workflow, state, permission, data-use, or acceptance behavior from similar features.
- If a requirement cannot be assigned to a business domain, shared capability, or external adapter, do not create an implementation task.

## Traceability

- Link each requirement through source, key requirement contract, design, API/data, implementation, tests/drift check, and acceptance evidence in the master-plan ledger.
- Update the master plan immediately after completing or blocking a task.
- Never mark simulated, stubbed, static, or TODO behavior as implemented or verified.
- Mark implemented, verified, and accepted as separate states and attach evidence for each transition.
- Do not mark a requirement verified when the drift report contains uncovered MUST clauses, violated MUST_NOT clauses, unsupported business behavior, or broken evidence links.
- Treat structural verification and semantic review as separate evidence; never claim the structural checker proves legal or business correctness.
- Structural mapping is not D-gate completion. A D gate may pass only when the current MVP Task has detailed design evidence and all blocking questions have evidence-backed answers in `docs/02-待确认事项清单.md`.

## Architecture Guardrails

- Agents orchestrate workflows; they do not own domain data, authorization, legal rules, or final decisions.
- Domain modules depend on ports, not DM8, vendor SDKs, model providers, or center API implementations.
- Keep current authorization separate from historical run evidence; do not freeze a full Runtime model before the target MVP needs it.
- Use only the minimum Claim/Evidence/Source/Citation relationships needed by the target MVP, with domain-specific semantics owned by the business domain.
- Keep business, task, review, and official states separate.
- AI may extract, classify, summarize, analyze candidates, and draft. Rules handle deterministic conditions. Authorized people or authoritative systems make final decisions.
- Agent or model adapters must not directly write permissions, review decisions, official states, or center source records.

## Uncertainty

- Do not silently invent business, legal, permission, data-retention, or external-system behavior.
- Record unresolved items in the plan/checklist with the blocker, owner, exit condition, and safe work that can continue.
- Never mark an item in `docs/02-待确认事项清单.md` confirmed without workspace evidence identifying its source, version, responsible party, date, scope, and conclusion.
- Preserve source results and machine output; human corrections create traceable versions rather than overwriting history.
- Record derived technical decisions as ADRs; never present them as statements from the original requirements.
