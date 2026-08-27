# Zapstore Android app spec

This is the app-level contract. It complements
`DATA_LOADING_ARCHITECTURE.md`, which defines the lower-level query behavior.

## Local data comes first

Every screen must render matching data already stored on the device as soon as
the local query returns it. A screen must not show a full-screen spinner while
waiting for any of the following:

- a relay connection, EOSE, timeout, or refresh;
- C1/APK verification;
- profile or other related-data enrichment;
- a second query started because the first query found local data.

Remote work is a background update. It may replace or enrich the visible data,
but it must not hide existing data. If there is no local data, an initial
placeholder or loading indicator is appropriate until the first remote result
or terminal failure.

## Enrichment does not control visibility

`hasVerifiedC1` and similar derived properties are optional presentation
metadata. They must not determine whether a locally available app is shown.
The base model is published first; enrichment may update it later.

This applies to app cards, app detail, stack members, search results, release
feeds, profiles, and related profile/zap/release data.

## Query policy

- Use `LocalAndRemote` for screen data unless the user explicitly requested a
  remote-only search or refresh.
- Use `RemoteMode.Stream` for live screen roots and `OneShot` for bounded
  related or paged requests.
- Keep current items when the query is loading, reconnecting, timing out, or
  failing.
- Set `*Loading` only when the corresponding visible collection is empty and
  its query is still loading.
- Cancel screen-owned queries when the screen/view model is disposed.

## Profile icon caching

Profile metadata and profile icons use the same freshness period:
`PROFILE_CACHE_DURATION` (currently one day). Profile icon requests must use a
stable memory and disk cache key during that period, then use a new key so the
icon can refresh. A cached icon should remain visible while profile metadata or
the next icon request is loading or unavailable.

## Reusable app-card pattern

Use `AppCard` from `components/CatalogCards.kt` for apps displayed in feeds,
search results, and stack members. It is the canonical compact app
representation:

- a responsive square app icon (21% of the card width, clamped to 50–68dp);
- a slightly enlarged, single-line app name that ellipsizes when needed,
  followed by a next-line, tappable `by` publisher treatment, including the
  publisher avatar;
- the release `VersionPill`, when available, before the publisher treatment on
  that second line;
- an optional plain-text summary, limited to two lines.

Keep the card's 20dp rounded surface, translucent surface fill, low-contrast
outline, 16dp content padding, 14dp icon-to-content gap, and 14dp app-icon
corner radius. The title's 23dp line height, 8dp gap, and second-line version
pill must match the app icon's height. Keep `VersionPill` as the shared version
treatment: 8dp corners, the `ZapVersionPill` background, and compact label
styling. Do not reimplement these elements in individual screens; extend the
shared composables when a common variation is needed.

## Screen rules

| Screen | Local-first root | Background work |
|---|---|---|
| Home | stacks, release feed, search results | live refresh, older pages, releases, profiles |
| App detail | app graph and latest release | live updates, profile, zaps/assets |
| Stack detail | stack and member apps | member refresh, profiles, comments |
| Profile | profile and published content | profile refresh, pages, related activity |
| Updates | installed/local state | operations and relay refresh |
| Search | matching local apps first | remote search expansion |

## Review checklist

When adding a screen or query:

1. Identify the local model that can be rendered immediately.
2. Publish that model before awaiting enrichment.
3. Keep it visible while remote work continues.
4. Verify empty-cache, populated-cache, timeout, and failure behavior.
5. Add a test proving populated local data is visible before remote completion.
