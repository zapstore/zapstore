# Zapstore — Quality Bar

## General Expectations

- Correct behavior matters more than coverage numbers.
- Happy-path-only implementations are insufficient.
- Failures must be explicit and observable.

## Layer Expectations

### models

- Parsing and serialization behavior must be tested.
- Unknown or future fields must be tolerated.

### purplebase

- Storage and query behavior must be testable without network access.
- Subscription, cancellation, and isolate behavior must be validated.

### zapstore UI

- UI state machines must be explicit and testable.
- Loading, empty, error, and retry states are mandatory.
- UI must remain usable under degraded network conditions.

## Implementation Expectations

- Follow existing patterns in the nearest module.
- Avoid introducing new architectural layers unless required by a spec.
- Do not perform broad or stylistic refactors.
- Prefer clarity and locality over abstraction.
- Prefer extending or reusing existing abstractions over introducing new ones.
- Code must be structured for human review first, not for AI generation convenience.

## Testing Expectations

- Tests must validate behavior, not implementation details.
- Failure, cancellation, and degraded-network paths must be covered.
- Tests that only assert the happy path are insufficient.

## Anti-Patterns

- Silent failures
- Blocking the UI thread
- Artificial delays or polling
- Large refactors unrelated to the task

## Working With AI (Human Guidelines)

This project uses a spec-first workflow to collaborate safely with AI.

### Documentation Discipline

- Markdown files must remain small, focused, and human-readable.
- Prefer extending or refining existing documents over creating new ones.
- New markdown files should be introduced only when unavoidable.
- The goal is to do more with less, not to document everything.

### What Humans Own

- Foundation specs under `specs/guidelines/`
- Feature specs under `specs/features/`
- Decisions to change behavior or architecture

### What AI Owns

- Execution plans under `work/**/task_plan.md`
- Decision logs and test matrices under `work/**/`

### When a Work Packet Is Required

- New features
- UX changes
- Async, lifecycle, or background work
- Security or verification changes
- Any non-trivial or risky change

### Spec-First Rule

- Behavior changes require updating the spec first.
- During implementation, specs are read-only.
- If a spec is unclear or incorrect, AI must stop and report a "Spec Issue".

### Task Plan Usage

- Humans create the initial task_plan with a rough checklist.
- AI refines the plan, executes tasks, and marks progress.
- Every code change must map to an item in the task_plan.

### Task Completeness

For non-trivial work, changes are not considered complete unless:

- task_plan.md reflects the actual work performed
- test_matrix.md demonstrates behavioral coverage
- no significant code exists outside the task plan

This workflow exists to prevent AI drift, accidental refactors, and UX regressions.
