---
date: YYYY-MM-DD
tags: [tag1, tag2, tag3]
problem: One-line description of the problem encountered
---

# DEC-XXX — Short Title

## Problem

What went wrong, or what was unclear. Concrete and specific.

## Context

Why this came up. What was being built. What made it non-obvious.

## Decision

What was chosen. One clear statement.

## Options Considered

- **Option A** — description, why rejected
- **Option B** — description, why rejected
- **Option C (chosen)** — description, why selected

## Rationale

Why this option fits this codebase, this team, this context.

## How to Avoid This Problem Next Time

Concrete rule or pattern an agent can follow automatically:
- Do X instead of Y
- When you see Z, always do W
- Reference: `path/to/example.go` shows the correct pattern

---

# Example: DEC-001 — Separate polling from local watching

---
date: 2026-02-04
tags: [state-management, providers, riverpod, skeleton]
problem: Mixed local/remote data in one provider caused skeleton showing during refreshes
---

## Problem

The updates screen showed a skeleton loading state during every pull-to-refresh, even when data was already loaded locally.

## Context

Building the updates screen. A single `CategorizedUpdatesNotifier` was both watching local DB and performing remote fetches. Timer-based invalidation triggered network requests on every rebuild.

## Decision

Split into two providers: one owns remote polling, one watches local data only.

## Options Considered

- **Single provider** — simpler, but mixing concerns caused skeleton/loading state confusion
- **Split providers (chosen)** — `UpdatePollerNotifier` (remote) + `CategorizedUpdatesNotifier` (local only)

## Rationale

Local provider is purely reactive — it never triggers network. Remote provider handles network + throttling. Clear separation means skeleton logic is simple: show only when no local data matches.

## How to Avoid This Problem Next Time

- Never mix `RemoteSource` and `LocalSource` queries in the same provider
- If a provider needs both local reactivity and remote fetching, split it
- Skeleton rule: `showSkeleton = installedIds.isNotEmpty && !hasAnyMatch` — once any match exists, never show skeleton again
- See `lib/services/updates_service.dart` for the correct pattern
