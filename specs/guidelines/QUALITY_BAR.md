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

## Testing Expectations
- Tests must validate behavior, not implementation details.
- Failure, cancellation, and degraded-network paths must be covered.
- Tests that only assert the happy path are insufficient.

## Anti-Patterns
- Silent failures
- Blocking the UI thread
- Artificial delays or polling
- Large refactors unrelated to the task

## Editing Policy (Human-Owned)
- These files are human-owned and change slowly.
- Keep them small, focused, and easy to reason about.
- Prefer explicit constraints over prose.
- Create new markdown files only when unavoidable.
