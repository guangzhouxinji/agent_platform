# Project Instructions

## Source Of Truth

- Read `docs/08-总开发实现计划.md` before starting design or implementation work.
- Use section 0 of that plan for the complete delivery route, section 7.8.3 for task status/dependencies, and section 8 for requirement evidence. After a task exits, continue with the next eligible task in that route; do not require the user to restate the next step.
- Do not prepend iteration journals or create another implementation plan. Historical proposals and `docs/evidence/document-consolidation-20261006/historical-reference.md` are read-only context, not current scheduling instructions.
- Preserve requirements, decisions, detailed designs and validation evidence when cleaning documentation; migrate references and save recoverable original bytes before deleting redundant planning files.
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

## Delivery Execution Contract

- On every implementation turn, reread this file and the current primary task, relevant task rows and latest handoff in `docs/08-总开发实现计划.md`. Resume from the recorded next action. Do not repeat a project-wide assessment unless new evidence invalidates the route or the user requests one. The latest explicit user restrictions, including paused deployment or physical operations, remain binding.
- Before editing, state the current delivery outcome in one sentence: what the user or acceptance operator will be able to do, and which existing evidence will prove it. When deployment is paused, name the reachable source/verification outcome and explicitly retain the undelivered runtime outcome. Do not turn a component check into a user-visible delivery claim.
- Limit each execution chat to one assigned primary delivery path and one necessary unblocker. Other chats may advance admitted tasks concurrently under the resource-scoped ownership rules below. Parent tasks may remain open; that is not permission to expand them all. A new blocker task must follow the existing task-registration rules and identify the exact delivery step it blocks, its evidence-backed necessity, its bounded exit condition, and the parent step to resume. Do not create generic infrastructure or additional tooling merely because it could be useful.
- After an unblocker passes its exit checks, return immediately to its parent delivery step. After that step exits, continue the next eligible step within the current turn. A successful test, new document, partial fix, or completed child task is not by itself a reason to end an authorized implementation turn.
- Distinguish engineering work from external decisions. Within existing authorization, Codex owns diagnosis, implementation, test setup, artifact preparation, recovery preparation and evidence collection. Do not ask the user to produce an artifact the implementation is meant to generate. Before requesting authorization, read and reconcile existing approvals; ask only for the exact missing scope, responsible party or business fact. Never widen an approval or bypass R/D, permissions, source rights, runtime admission or safety checks to meet a delivery target.
- Arrange short-lived activation material and other expiring user handoffs only after their dependent service and workflow are ready. Preserve the approved TTL and identity contract. Do not issue short-lived material early and leave the user waiting on unrelated engineering work.
- At every checkpoint, compare the parent delivery step with its previous checkpoint: a real operation succeeded, a necessary blocker was removed, a failed hypothesis was eliminated with evidence, or no delivery progress occurred. Test counts, new files and new task counts alone do not constitute delivery progress. Record this comparison briefly in the existing task row/handoff, not a separate status ledger.
- If two consecutive implementation checkpoints make no delivery progress, perform a bounded path review before adding more fixes: identify the first unresolved dependency, remove only dependencies disproved by evidence, and select the shortest already-admitted route. Do not repeat an unchanged failed operation or another full planning cycle. Continue safe authorized work; if all routes require external input, report that precise blocker. This rule never permits weakening acceptance or treating unknown behavior as safe.
- Allow multiple execution owners for shared operations when their files, resources and side effects do not conflict. Ownership is assigned per task and resource scope, not exclusively to one project-wide chat. This user-authorized rule supersedes blanket sole-owner restrictions in earlier task rows and handoffs; approved task write sets, dependencies, R/D gates, source rights and explicit operation pauses remain binding. An active consultation chat alone does not block admitted enforcement or adjudication work.
- Before concurrent work, check the current task rows, latest handoffs and relevant active operations. State the task, exact write set, output locations and affected runtime/database resources in the existing task row or handoff. Non-conflict must cover shared dependencies, verification inputs, generated files, ports, locks and resource capacity, not just different business domains. If overlap or ownership is uncertain, inspect that specific scope and coordinate only the conflicting operation; continue independent work.
- Serialize writes to the same physical file or generated output, including different rows of the master plan. Reread the latest bytes and apply only the intended change, preserving other owners' edits; never overwrite a file from an older snapshot. Different owners may update their own task rows without transferring the whole project's execution ownership. Shared progress generation must use stable plan input and an exclusive output write interval.
- Give concurrent builds and checks separate output/report locations and stable input snapshots or bounded input freezes. Do not mutate another active check's inputs or reuse its output directory. Avoid duplicate verification of the same unchanged candidate; independently scoped verification may run concurrently within available capacity. Database, deployment and maintenance operations may overlap only when their authorized targets, transaction/lock effects and recovery actions are independent; operations affecting the same runtime, schema, records, shared volume or maintenance lock require serialization. Do not interrupt another owner's process to obtain a resource.
- Analysis/review chats must respect each active owner's mutable scope. Delegation, messages to other chats and automation still require their applicable authorization; concurrent ownership does not itself grant it.
- Keep required pre/post `verify-all` checks in the responsible scope owner's workflow, using stable inputs and separate evidence outputs under the concurrency rules above. During a change, run meaningful failing examples and affected regression; once those checks pass, repeat or broaden only for new changes, failures, uncovered risk or an existing required gate. Preserve full-suite failures and skipped scope. Do not add tests that merely restate these instructions, and do not claim interrupted or concurrently changing-source checks as a completed baseline.
- Before an unavoidable stop, update the existing master-plan task/handoff with: the delivery outcome; last proven step and evidence; exact failure or blocker and owner; live process/lock state when relevant; next concrete action, prerequisites and expected observation. Preserve original failures and unknown side effects. Do not make the user reconstruct context or choose the technical order again.
- Final implementation reports lead with what became usable, or explicitly say the delivery remains incomplete. Then state the remaining blocker, next action and whether a specific user decision is required. Report source-implemented, component-verified, deployed, real-flow-verified and business-accepted separately. If stopped by execution limits, say so instead of presenting those limits as missing business authorization.
- Repository instructions govern execution but do not create a background scheduler. Do not promise work after a turn ends unless a continuation mechanism has actually been configured and confirmed. Any authorized continuation must target the existing execution chat, reread the current plan, respect pauses and expired scope, and avoid duplicate runs or repeated unchanged-blocker notifications.

## Value And Repetition Checks

- Before starting a work item, answer four questions briefly using the existing task row and evidence: which current delivery gap does this close; why is it necessary now; what existing implementation or result can be reused; what observable result will end this work? If these cannot be answered, do a bounded inspection rather than starting implementation or another infrastructure task. Do not create a separate scorecard or planning document for these answers.
- Search the relevant implementation, completed tasks and latest evidence before adding an API, script, dependency or test facility. Different names do not prove different capabilities. Reuse valid existing behavior; register only the actual delta. Reuse verification evidence only when its source/input/configuration, scope and required freshness still apply. This never replaces mandatory pre/post checks, fresh runtime admission or current authorization.
- Every repeated expensive build, full test suite, scan or physical attempt must have a stated trigger: changed relevant input, a repaired failure, newly available prerequisite, required verification gate or a new hypothesis distinguishable by the result. An unchanged failed command must not be rerun merely because a new turn began. Prefer focused diagnosis and affected regression before a full run. Do not rebuild an unchanged candidate or reread historical evidence already resolved in the current handoff.
- When investigation consumes about 30 minutes without narrowing a cause or changing the delivery state, review the hypothesis and method at the next safe boundary. This is a diagnosis checkpoint, not a timeout that kills tests, abandons a recovery sequence or expires approved work. Two successive checkpoints with the same parent blocker require a route review even if additional low-level tests or hypotheses were recorded; do not use micro-fixes to hide stalled delivery.
- Report the before/after difference in the existing handoff: capability enabled or necessary blocker removed, evidence, remaining delivery gap and next action. Reused evidence and genuinely new execution must be distinguished. Documentation-only work counts as delivery progress only when it resolves an actual scheduling, contract or approval ambiguity; it does not count as product capability. Keep routine reports concise and do not repeat an unchanged project summary.
