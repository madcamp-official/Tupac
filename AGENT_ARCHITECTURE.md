# Agent runtime architecture

> Implementation snapshot: 2026-07-29. This document describes the current
> Kotlin implementation, not a target framework design.

## Runtime decision and trust boundary

The app has one learned planner: Gemini. Deterministic Android-side
interceptors and policies may act before or reject the planner, but there is no
fallback local LLM/VLM, model-profile resolver, llama.cpp runtime, or LiteRT-LM
runtime. A missing Gemini API key is an explicit configuration error.

Gemini receives a structured planning request and returns a structured action
envelope. `GeminiAgentRunner` validates that envelope and manually maps it to a
`DeviceToolCall`; the app does not currently expose `DeviceToolRegistry` as
native Gemini function declarations or persist native function-call history.
This still follows Gemini's core responsibility boundary: the model proposes a
call and the application executes it. Gemini's
[function-calling documentation](https://ai.google.dev/gemini-api/docs/function-calling)
describes that boundary, while its
[structured-output documentation](https://ai.google.dev/gemini-api/docs/structured-output)
explicitly requires application-side semantic validation even when the JSON
shape is valid.

Observation, OCR, privacy routing, credential resolution, action validation,
policy enforcement, tool execution, and durable local state remain on the
device.

## Runtime ownership

The main implementation units are:

- `AgentRunCoordinator`: invocation orchestration and terminal ordering.
- `AgentBootstrapper`: typed configuration validation and internally
  consistent invocation setup.
- `AgentGoalInterpreter`: one model call that converts free-form intent into an
  app-independent `AgentGoalSpec`.
- `GoalSpecTaskContractCompiler`: validates the model-authored spec and
  combines it with optional skill accelerators.
- `AgentRunContextResolver`: skill discovery and model-independent run-context
  assembly.
- `GeminiAgentRunner`: bounded observe-plan-act-verify loop.
- `AgentActionDispatcher`: correlated, write-ahead dispatch for side-effecting
  Android tools.
- `RetryingAgentPlanner`: bounded transient retry around a planner turn only.
- `AgentWorkspace`: durable state model and pure reducer.
- `AgentWorkspaceSession`: converts trace events into workspace transitions.
- `FileAgentWorkspaceStore`: atomic snapshot and checkpoint persistence.
- `AgentRunLogStore`: per-invocation diagnostic trace.
- `AgentDataSanitizer`: persistence-boundary redaction.

The critical event order is:

```text
GeminiAgentRunner
  -> AgentWorkspaceSession.consume(event)
     -> reduce current workspace
     -> revision check
     -> FileAgentWorkspaceStore.save(...) when state changed
  -> AgentRunLogStore.recordTrace(event)
  -> external progress callback
```

For side-effecting tools, `AgentActionDispatcher` creates a `callId`, adds the
current snapshot ID, and awaits the `ToolCall` trace before invoking the local
tool. The session therefore commits a correlated `pendingAction` before the
side effect. It then awaits the correlated `ToolResult` trace after execution.

A successful, screen-changing `ToolResult` deliberately does **not** clear the
pending action. The next fresh observation produces an `ActionVerification`
event, and that correlated verification—successful or unsuccessful—clears it.
Errors, screenshots, observations, `wait`, and other results that do not await
screen verification clear or bypass pending state according to their result
type. This is a write-ahead verification protocol, not an exactly-once
transaction.

The UI repository is not the owner of agent state. It only receives progress
and the final outcome from `AgentRunCoordinator`.

## Invocation and resume flow

Bootstrap is an explicit stage rather than an implicit prelude to the planner
loop. `AgentBootstrapper.bootstrap(...)` returns one of three typed results:

- `Ready` carries `NEW` or `RESUMED` mode, the begun workspace session, the
  per-run audit log, and the resolved run context;
- `SetupRequired` reports a typed `EMPTY_GOAL` or `API_KEY_MISSING`
  configuration issue without starting a durable run;
- `Failed` reports `INITIALIZATION_FAILED` with its cause after attempting
  partial-initialization cleanup.

`AgentRunCoordinator` converts the two non-ready results to an
`AgentBootstrapException`; only `Ready` enters the runner.

1. The bootstrapper validates the trimmed goal and API key.
2. On `Dispatchers.IO`, it loads the compact task-skill activation catalog and
   `gui-app-navigation`, then checks for a compatible durable workspace.
3. A new goal is interpreted by Gemini into open-schema entities, constraints,
   preferences, success criteria, forbidden actions, and assumptions. A
   resumed workspace reuses its validated persisted `AgentGoalSpec`, avoiding
   another interpretation call.
4. Task skills are activated from semantic entities such as `app`, never from
   raw-goal regexes. `GoalSpecTaskContractCompiler` maps only already-structured
   semantic fields into generic runtime policies; selected skills contribute
   optional UI hints and capabilities.
5. `FileAgentWorkspaceStore.openOrCreate(goal, skillDigests)` reopens the most
   recently updated `ACTIVE` or `PAUSED` workspace only when both the SHA-256
   identity of the original normalized goal and the complete skill-digest map
   match. The raw goal is not used as the persisted lookup key.
6. Same-goal resumable workspaces with incompatible skill digests are marked
   `STALE`, their pending intent is cleared, and a new workspace is created.
   The create callback determines bootstrap mode: a newly created workspace is
   `NEW`; reusing a compatible workspace is `RESUMED`.
7. The bootstrapper creates the per-run log and session,
   `AgentWorkspaceSession.begin(runId)` marks the workspace active, and the
   first audit event records `run_start` with `bootstrap_mode`.
8. `GeminiAgentRunner` starts a fresh bounded loop. Bootstrap deliberately does
   not probe live UI readiness: the runner's first `observe_ui` is the single
   authoritative observation for grounding and resume reconciliation, avoiding
   a duplicate observation that could already be stale.
9. Every workspace-changing trace is synchronously reduced and saved before it
   is forwarded to the invocation log and UI callback. Route-only and
   screenshot-result traces are deliberate workspace no-ops.
10. After the runner returns, normal terminal handling first commits
   `session.finish(outcome)` in `NonCancellable + Dispatchers.IO`, then attempts
   `run_end` and log closure.
11. Cancellation and thrown failure use a separate non-cancellable finalizer
   that attempts `session.cancel` or `session.fail`, an audit error record, and
   log closure.

Bootstrap cleanup is state-aware and also runs in
`NonCancellable + Dispatchers.IO`. If a run log was created but
`session.begin` did not complete, the bootstrapper attempts to close and delete
that partial log. If the run began, it attempts a cancelled or failed workspace
terminal update, records the error when possible, and closes the log. Cleanup
failures are attached to the initiating exception rather than replacing it.

Normal terminal ordering is deliberately asymmetric. If a successful outcome
has already committed workspace status `COMPLETED`, a later `run_end` audit-log
failure is logged and log closure is still attempted, but the outcome is
returned and the workspace is not downgraded through `session.fail`. This
best-effort rule is specific to terminal audit logging; a run-log failure while
recording a start or an in-loop trace still propagates through the applicable
bootstrap or abnormal-finalization path.

Resume remains **restart, reopen, re-observe, and re-plan**, with one limited
reconciliation step. If a compatible workspace contains a pending action, the
runner compares its saved pre-action package and semantic signature with the
first fresh observation. It never blindly dispatches that action again:

- observed package or semantic change emits a resumed, verified
  `ActionVerification`, clears pending state, and continues;
- no observable change emits an unverified `ActionVerification`, clears pending
  state, and pauses for user handoff.

This is conservative process-death reconciliation. It is not
instruction-pointer restoration, native tool-call replay, or tool-specific
effect recovery.

## Durable workspace

`AgentWorkspace` stores:

- a redacted goal and a SHA-256 identity of the original normalized goal;
- a redacted, validated model-authored `AgentGoalSpec`;
- workspace status, current run ID, revision, and timestamps;
- an advisory plan;
- facts with source and confirmation metadata;
- discovered candidates, per-kind collection status, and verified selections;
- a screen/package resume point with a semantic signature;
- recent failures;
- one correlated pending tool intent with call ID, audit idempotency key,
  pre-action state, expected change, and target key;
- loaded skill names and SHA-256 skill digests;
- up to 50 run IDs and the completed-action count;
- schema version `8`.

New workspaces begin with three coarse plan stages: ground, execute, and
verify. The first observation completes the initial grounding step and
activates execution. Planner decisions can revise the remaining advisory plan,
and terminal success marks all steps complete. User facts derived from the task
contract are confirmed and are not overwritten by later screen or model facts.
Model progress is explicitly unconfirmed.

Current observation reduction extracts contract view facts and all matching
showtime nodes in the current accessibility snapshot. A non-empty extracted
set is labeled `COMPLETE_SNAPSHOT`, sorted deterministically, and used to
derive the earliest showtime independent of tree order. When a later snapshot
contains no matching showtime, the candidates, earliest-showtime fact, and
showtime selection are removed and the collection is marked `INVALIDATED`.
`COMPLETE_SNAPSHOT` means complete for the nodes present in that one snapshot;
it does not prove that off-screen, paginated, or not-yet-loaded options were
enumerated.

A verified `ActionVerification` can persist a validated selection when the
pending target key matches a durable candidate. For a candidate kind that
still has values, the prompt includes its collection status and at most 24
candidate values.

`promptSection()` gives Gemini a compact durable summary rather than the full
event history. It instructs the planner to revalidate screen-derived facts
against the fresh observation and never reuse an old accessibility node ID.
The reducer does not persist node IDs as durable selections. Diagnostic run
logs may still contain node IDs from redacted observations.

Storage is app-private:

```text
filesDir/
  agent-workspaces/<workspace-id>/
    workspace.json
    events.jsonl
    checkpoints/checkpoint-XXXXXXXX.json
  agent-runs/
    run-<run-id>.jsonl
```

`workspace.json` is the primary authoritative resume snapshot and is replaced
with Android `AtomicFile`. Selected lifecycle events, including tool intent,
tool result, action verification, skill staleness, and invocation boundaries,
also request atomic checkpoints; at most 20 are retained. If the main snapshot
cannot be decoded, the store tries the newest decodable checkpoint.
`events.jsonl` is never used to reconstruct state.

`events.jsonl` contains only event metadata such as workspace ID, revision,
event name, and timestamp. It is not an executable replay log. The separate
run log starts with the redacted goal, model, workspace, skill IDs, persisted
goal spec, `bootstrap_mode` (`NEW` or `RESUMED`), and goal-interpretation
latency/token metrics or a cache-hit marker. It then contains trace payloads
and metrics for one invocation. Persisted UI observations and sensitive tool
arguments/results pass through their redaction paths; screenshot pixels are
not persisted. This is not a general data-loss-prevention guarantee for every
free-form planner field. Dispatched side-effect calls, results, and action
verifications are correlated by call ID; the log also records the audit
idempotency key and whether verification occurred during resume
reconciliation.

Every save reads the latest decodable snapshot and requires the next revision
to be exactly `persisted.revision + 1`; new workspaces must begin at revision
`0`. When the newer base revision is already visible at check time, a stale or
duplicate writer raises `AgentWorkspaceRevisionConflict` instead of silently
overwriting it. Snapshot replacement is the commit boundary. Checkpoint and
event-index writes happen afterward on a best-effort basis and log failures
without rolling back the committed snapshot.

The schema decoder accepts supported historical versions through `8` and
supplies compatibility defaults for newer fields. Future versions above `8`
are rejected. Any decoded resumable
workspace whose persisted goal key lacks the `sha256:` prefix is marked
`STALE` rather than auto-resumed; this prevents two distinct private goals that
redact to the same text from sharing a workspace. Skill compatibility is
evaluated separately: each digest covers the skill ID, instructions, and
canonicalized runtime policy. Exact digest-map equality is required before a
same-goal workspace is reused.

Android's [`AtomicFile` documentation](https://developer.android.com/reference/android/util/AtomicFile)
defines the file-replacement primitive used here. The store adds a
process-local synchronized lock around it.

## Observe-plan-act-verify loop

`GeminiAgentRunner` runs serially for at most 24 planner turns.

The runner remains the owner of the state machine, but its implementation
separates the major turn stages:

- `observeWithRetry` performs observation, bounded transient retry, and
  observation-result tracing;
- `preparePerception` classifies privacy before pixels, conditionally performs
  on-device OCR, reclassifies OCR-enriched content, and produces the
  planner-safe snapshot and elements;
- `requestPlannerTurn` builds the planner request, handles privacy-gated
  `request_visual`, and returns an explicit `Decision`, `Retry`, or `Stop`;
- `run` owns resume reconciliation, deterministic interceptors, planner-result
  handling, runtime guards, validation, dispatch, and next-turn verification.

At the start of each turn it:

1. obtains a fresh accessibility observation;
2. retries transient `UI_TREE_UNAVAILABLE` failures up to five times;
3. checks the previous screen-changing action using model-independent semantic
   evidence: package, durable UI content, control state, configured task-state
   slots, and newly observed keywords from the expected change;
4. applies privacy classification and, when allowed and useful, on-device OCR;
5. applies deterministic interceptors and policy guards;
6. sends the safe observation, compact recent action history, task contract,
   skills, current workspace summary, and agent-host package to Gemini;
7. rejects a planner `wait` without dispatch when the foreground package is the
   agent host and its UI contains a recognized generation/progress label, then
   feeds corrective history into the next planner turn;
8. validates and maps the structured planner action to a local device tool;
9. dispatches a side-effecting tool through `AgentActionDispatcher`, which
   persists its correlated intent, executes it on `Dispatchers.IO`, and
   persists its result;
10. keeps successful screen-changing actions pending until the next observation
   emits their correlated semantic verification.

Repeated unchanged actions, identical state/action cycles, A-B-A-B cycles,
invalid targets, modal and selection stalls, task-state mismatches, goal
invariant violations, model-authored forbidden actions, and the global
irreversible-action boundary can correct or stop the loop. Action-specific
missing arguments are returned to the planner as recoverable feedback.

Node-targeted core tools require the exact `snapshot_id` that supplied the
node. There is no implicit process-global latest-snapshot fallback. The
bounded in-process observation store may evict an old snapshot, but eviction
causes a safe `OBSERVE_UI_REQUIRED` failure rather than redirecting the action
to another caller's latest screen. Planner text input also requires an exact
editable `node_id`; it never falls back to the focused or first input field.
Before `set_text` executes, the tool resolves that node in the named snapshot,
requires it to be visible, enabled, and editable, and recaptures the screen.
A package or fingerprint mismatch returns `SCREEN_CHANGED` instead of typing
into a possibly different field.

The host-UI guard is deliberately narrow: it applies only when the observed
package is the agent's own package and a node label matches the configured
generation terms such as `답변 생성 중`, `화면 판단`, `generating`, or
`analyzing screen`. Other `wait` proposals continue through the ordinary
planner-action path.

A planner `finish_success` action is only a proposal. A skill with declared
completion labels requires those labels and any required validated selection.
A generic task requires the planner to cite an exact currently visible
completion label; an empty skill contract never auto-completes. Ordinary tool
success means that the local tool returned success. The next-turn
`AgentActionVerifier` ignores layout-only fingerprint
noise and requires observable semantic progress, but it is still not proof
that every action-specific expected postcondition was satisfied.

## Retry and coroutine boundaries

The default runner wraps `GeminiApiClient` in `RetryingAgentPlanner`. One
stateless planner decision is attempted at most three times. `IOException`,
HTTP `429`, and HTTP `5xx` failures are retried with exponential delays starting
at 500 ms. Cancellation and non-transient client failures are propagated
without retry.

This boundary is planner-only: it never retries an Android side effect.
Accessibility observation has its own separate, narrow retry for
`UI_TREE_UNAVAILABLE`; it is not a general tool retry policy.

Workspace/run-log bootstrap and trace commits run on `Dispatchers.IO`. Both
normal terminal commit and cancellation/failure or partial-bootstrap cleanup
use `NonCancellable + Dispatchers.IO`. Device-tool dispatch and direct
observation/screenshot execution also use `Dispatchers.IO`, as does the Gemini
HTTP client. External trace callbacks run only after durable-state and run-log
work completes. Kotlin's
[`Dispatchers.IO` documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html)
describes the blocking-I/O dispatcher used for these boundaries.

## Privacy, credentials, and irreversible actions

Privacy classification runs before OCR or screenshot capture.

- `CLOUD_OK`: the safe accessibility observation may be sent to Gemini.
  Conditional OCR and a visual follow-up request are allowed.
- `CLOUD_REDACTED`: editable and sensitive values are redacted. OCR fusion is
  discarded if reclassification becomes sensitive, and screenshots are not
  sent.
- `USER_HANDOFF`: payment credentials or other protected input stop the
  autonomous run for user action.

Authentication handling is deterministic and local. Approved,
package-bound credentials are resolved through the local credential path and
filled with `FillSecretDeviceTool`; secret values are not included in Gemini
prompts, workspace tool arguments, or persisted tool-call traces. Credential
storage uses Android Keystore-backed protection.

Final order, purchase, and payment controls remain blocked by executable
policy. `AgentDataSanitizer` also redacts secret-tool arguments and common
phone, email, and card-number patterns before diagnostic persistence.

## Current guarantees

The current implementation guarantees the following on the normal traced
execution path:

- Gemini is the only learned planner; all device tools execute locally.
- Planner actions are schema-parsed, semantically validated, and policy-checked
  before execution.
- Execution is serial and bounded to 24 planner turns.
- The planner loop starts only from a typed bootstrap `Ready` result; its
  `NEW` or `RESUMED` mode is written to the `run_start` audit event.
- Bootstrap performs no live UI probe. The runner's first observation is the
  authoritative readiness, grounding, and resume-reconciliation boundary.
- A fresh UI observation grounds every turn, and a later observation requires
  model-independent semantic evidence before accepting a screen-changing
  action as progress.
- Every dispatched side effect has a correlated call ID. Its pending intent,
  audit idempotency key, pre-action state, and expected change are atomically
  saved before the local executor is invoked.
- Successful screen-changing results remain pending after `ToolResult`; the
  correlated `ActionVerification` clears them after the next observation.
- A compatible pending action recovered after process death is never
  automatically re-executed. Package/semantic change allows continuation;
  ambiguity pauses for user handoff.
- Workspace snapshots use atomic replacement and can fall back to retained
  atomic checkpoints.
- Writes whose revision is not exactly one greater than the disk-visible
  persisted revision at check time are rejected.
- The latest resumable workspace for the same hashed normalized-goal identity
  and exact skill digests can be reopened on a later explicit invocation.
  Incompatible same-goal workspaces become `STALE`.
- Supported historical workspace schemas are tolerantly decoded through
  schema `8`; future schema versions are rejected.
- Showtime candidate completeness and invalidation are explicit at the current
  accessibility-snapshot scope.
- Confirmed user facts are not overwritten by observation or model progress.
- Old accessibility node IDs are not persisted as durable selections and are
  not treated as valid grounding after a fresh observation.
- Node clicks, scrolls, credential fills, and planner text input require an
  explicit observed `snapshot_id`; exact text input also requires the exact
  visible, enabled, editable `node_id` from that snapshot and refuses a changed
  package or fingerprint.
- A `wait` proposed on a recognized agent-host generation surface is fed back
  to the planner and is never dispatched as a device action.
- Gemini cannot self-declare task completion; current screen evidence must
  satisfy the deterministic completion evaluator.
- Sensitive screenshots and secret plaintext are excluded from the planner
  path, and screenshot pixels are excluded from the run log.
- The default Gemini planner retries only transient planner failures and never
  retries a side-effecting Android action.
- Blocking workspace, log, tool, and Gemini HTTP operations identified above
  run on `Dispatchers.IO`; terminal finalization and partial-bootstrap cleanup
  are attempted in a non-cancellable I/O context.
- After a successful outcome commits `COMPLETED`, terminal `run_end` or log
  closure failure does not downgrade that durable status or replace the
  returned outcome.
- Cancellation and thrown failures preserve an in-doubt pending action and
  produce a best-effort workspace/run-log outcome when the process remains
  alive long enough to run the coordinator's handler.

## Deliberately narrow current scope: not guaranteed

The implementation intentionally makes no stronger claim than the guarantees
above.

### Resume and side-effect semantics

- There is no exact continuation at a program counter or plan step.
- There is no exactly-once tool execution. `callId` and `idempotencyKey` are
  durable correlation/audit identifiers, but the device executor does not
  consume them, maintain a deduplication ledger, or enforce idempotency.
- Process-death reconciliation compares only the saved package and compact
  semantic signature. It does not retain the full pre-action snapshot, require
  the exact expected change, query tool-specific effect state, or reconstruct a
  missing `ToolResult`.
- Any package/semantic difference is enough to mark a recovered action
  verified. No difference clears the pending marker and pauses; the runtime
  cannot distinguish “never dispatched” from “dispatched with no visible
  effect.”
- Skill-digest incompatibility marks the old workspace stale and clears its
  pending action without first performing effect reconciliation.
- There is no compensation/rollback protocol for completed device actions.
- There is no WorkManager job, foreground service, or other scheduler that
  automatically resumes a workspace after process death or reboot.
- Cancellation maps to `PAUSED`; `CANCELLED` is not currently used.
  `STALE` is reserved for skill-incompatible resumable workspaces.

These limits matter because durable agent runtimes generally provide
at-least-once rather than exactly-once tool semantics. Google ADK's
[resume documentation](https://adk.dev/runtime/resume/) warns that an
interrupted tool can run again, while LangGraph's
[fault-tolerance](https://docs.langchain.com/oss/python/langgraph/fault-tolerance)
and [Functional API](https://docs.langchain.com/oss/python/langgraph/functional-api)
documentation require replay-safe, idempotent side effects. The current custom
runtime adds conservative visual reconciliation but not executor-enforced
idempotency or tool-specific recovery contracts. Android
[WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)
would be the platform primitive to evaluate if automatic persistent scheduling
becomes a requirement.

### State-machine depth

- The durable plan is advisory context, not an executable workflow graph.
  Initial observation advances `ground` to completed and `execute` to active,
  but arbitrary planner-revised steps still lack per-step executable
  postconditions; successful terminal completion marks the whole plan complete.
- Candidate extraction is currently showtime-specific. `COMPLETE_SNAPSHOT`
  covers only matching nodes present in the current accessibility snapshot, not
  a scroll/pagination traversal, and the planner prompt truncates values to 24.
- `PARTIAL` is available for compatibility/defaulting but is not currently
  assigned by live showtime collection. When an empty collection is
  invalidated, its explicit `INVALIDATED` status remains durable but is omitted
  from `promptSection()` because that serializer iterates only candidate kinds
  that still have values.
- Verified target matching can populate selections, but generic candidate kinds
  and blocked-step transitions are not implemented.
- `AgentOutcome` has typed `disposition` and `stopReason`, but neither
  constitutes a persisted, versioned workflow protocol.
- The screen-transition verifier accepts any supported semantic evidence. It
  does not require the planner's exact expected change to be observed for every
  action.
- Different normalized-goal identities do not share long-term memory.

Google ADK separates short-lived session state from searchable long-term
[memory](https://adk.dev/sessions/memory/) and commits state changes through its
[event loop](https://adk.dev/runtime/event-loop/). The current workspace
implements a small custom session-state layer only; it does not depend on ADK
and does not provide ADK memory or workflow semantics.

### Persistence and compatibility

- The exact-next-revision guard detects stale or duplicate writers after a
  newer revision is visible. It is not an atomic cross-process compare-and-swap:
  the lock is process-local, so simultaneous processes can still race between
  reading and atomically replacing the file.
- A revision conflict fails the write; there is no automatic reload, merge, or
  retry policy.
- `events.jsonl` is diagnostic metadata, not a source for deterministic replay.
  Checkpoint or event-index failure after a snapshot commit is logged and does
  not fail or roll back that commit.
- The per-run JSONL log is append-only but is not an atomic transaction log and
  has no implemented rotation or retention policy.
- Best-effort tolerance applies only after the normal terminal workspace
  commit. A failure writing `run_start` or an in-loop trace can still fail
  bootstrap or the active invocation.
- Bootstrap and terminal cleanup are in-process best efforts. Abrupt process
  death can prevent those handlers from running or completing.
- Historical schemas use tolerant field defaults rather than explicit,
  version-by-version migrations. Compatibility beyond `8`, downgrade behavior,
  workflow-version stamps, and tool-version stamps are not implemented.
- A decoded workspace with a pre-hash goal key or without the exact current
  skill-digest map is staled rather than resumed under ambiguous or changed
  instructions.
- If neither the main snapshot nor a retained checkpoint decodes, that
  workspace cannot resume; a later invocation may create a fresh workspace.

LangGraph's
[checkpointer documentation](https://docs.langchain.com/oss/python/langgraph/checkpointers)
is a useful comparison for thread-scoped checkpoints and explicit durability
modes. The current file store is a smaller snapshot/checkpoint mechanism, not a
LangGraph-compatible replay store.

### Operations and data protection

- Planner retry is limited to three attempts for `IOException`, HTTP `429`, and
  HTTP `5xx`. It has no jitter, `Retry-After` handling, persisted retry budget,
  or retry of schema/parse failures.
- There is no persisted human-confirmation token that can resume inside the
  same planner call.
- Pattern-based sanitization is defense in depth, not a proof that arbitrary
  user-entered PII cannot appear in an unrecognized text field or free-form
  planner plan/progress field.
- Workspace and run-log files rely on app-private storage; they are not
  independently encrypted or cryptographically integrity-protected.
- Native Gemini function declarations, native function-call history, parallel
  branches, and framework-managed workflow replay are not implemented.

## Framework position

This runtime is a custom Kotlin orchestration loop. It borrows the useful
separation found in official agent guidance—model proposes, application
validates and executes, state is committed around events—but it is not an
integration with Google ADK or LangGraph.

Adopting a framework is not required to preserve the current Android privacy
and execution boundary. Claiming stronger durable-agent behavior, however,
would first require executor-enforced idempotency and deduplication,
tool-specific reconciliation and postconditions, cross-process single-owner
execution, explicit version-by-version migrations, and a persistent scheduling
policy.
