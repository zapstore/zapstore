# Zapstore — Quality Bar

## General Expectations

- Correct behavior matters more than coverage numbers.
- Happy-path-only implementations are insufficient.
- Failures must be explicit and observable.

## When to Create a Feature Spec

Create a spec if the work:

- Touches async/lifecycle code (risk of UI blocking or resource leaks)
- Modifies security-sensitive flows (verification, permissions, signing, secrets)
- Changes state machine behavior (package manager, auth, subscriptions)
- Affects multiple screens or services
- Could regress existing UX

**Skip the spec** if:

- Pure UI cosmetics (colors, spacing, copy changes)
- Adding a field to an existing model with no behavioral change
- Bug fix with obvious cause and obvious solution
- Dependency update with no API changes

When in doubt, create a spec. The overhead is low.

## Work Packet Lifecycle

1. Create `WORK-XXX-*.md` when starting non-trivial work
2. Update tasks and decisions as you work
3. **Delete after PR merges** — the feature spec remains as the contract

If multiple phases: `WORK-005-a.md`, `WORK-005-b.md` (same feature number).

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

## Working With AI

This project uses a spec-first workflow to collaborate safely with AI.

### Documentation Discipline

- Markdown files must remain small, focused, and human-readable.
- Prefer extending existing documents over creating new ones.
- The goal is to do more with less, not to document everything.

### What Humans Own

- Guidelines under `spec/guidelines/` (never AI-modified)
- Feature specs under `spec/features/`
- Decisions to change behavior or architecture

### What AI Owns

- Work packets under `work/`
- Refinement of task plans during implementation

### Spec-First Rule

- Behavior changes require a feature spec first.
- During implementation, specs are read-only.
- If a spec is unclear or incorrect, AI must stop and report a "Spec Issue".

### Task Completeness

For non-trivial work, changes are not complete unless:

- Work packet reflects the actual work performed
- No significant code exists outside the task plan
- Edge cases and failure modes are addressed

This workflow exists to prevent AI drift, accidental refactors, and UX regressions.
