# Zapstore Linux Plan

> Ref: [zapstore/zapstore#334](https://github.com/zapstore/zapstore/issues/334)

## The Challenge

Supporting Linux is not just a client port. Per [Fran's feedback](https://github.com/zapstore/zapstore/issues/334#issuecomment-3973268157), it requires three pillars:

1. **Indexing apps** — populating the relay with Linux app events
2. **Publishing support in zsp** — letting developers publish Linux apps
3. **Desktop client** — building, maintaining, and testing the zapstore app on Linux

Linux compounds this with distro fragmentation — many versions, more moving parts.

## Design Principle: Packaged Apps Only

NIP-82 is deliberately scoped to **self-contained packaged apps** — APK, AppImage, Flatpak — not distro-native package formats (rpm, deb, pacman, etc.). This is a core design decision learned from building the Android zapstore:

- **APKs guided the thinking.** The APK model — single self-contained binary, hash-verified, signed by developer, runs on any Android device — is the template. AppImage is the Linux equivalent: one binary, bundles its own dependencies, runs anywhere.
- **No dependency resolution, ever.** Zapstore is not a package manager. It does not resolve dependency trees, manage shared libraries, or interact with system package databases. That's the "dependency shitshow" (Fran's words) that NIP-82 explicitly avoids.
- **Tradeoff: more bandwidth for simplicity.** AppImages are larger than distro packages because they bundle everything. That's the price of portability and simplicity. Same tradeoff APKs make vs split APKs.
- **The event model is format-agnostic.** zsp's NIP-82 event structure (kind 32267 Application + kind 30063 Release + kind 3063 SoftwareAsset with SHA-256 hash, MIME type, platform tag) works identically for APK and AppImage. The format is just a different `m` tag and `f` tag.

This principle should be reflected in the NIP-82 title/scope (per Fran).

## Decision: Package Format

**AppImage primary. Flatpak as future secondary. No sub-flavors.**

If a distro doesn't support AppImage, it's out. This keeps scope manageable and aligns with NIP-82 which already defines `application/vnd.appimage` as a supported MIME type and `linux-x86_64` as a platform identifier.

Rationale:
- AppImage = download and run. No daemon, no root, no store dependency.
- Closest to how zapstore already works on Android (download binary, verify hash, install)
- Flatpak would require integrating with the Flatpak daemon — a different install model entirely

**Known compatibility caveat**: AppImage Type 2 requires `libfuse2`, which is not installed by default on Ubuntu 22.04+ (ships `libfuse3` only). Other distros vary. The client must detect this at first launch and show a clear error with install instructions (`sudo apt install libfuse2` or equivalent), rather than failing silently. `flutter_secure_storage_linux` also requires `libsecret` (standard on GNOME, may need explicit install on minimal distros). These dependencies should be checked at startup and surfaced as actionable user messages.

---

## Pillar 1: Indexing — Where Are the Apps?

### Ecosystem Size

| Format | Apps | Primary Source |
|--------|------|---------------|
| **Flatpak** ([Flathub](https://flathub.org)) | ~3,243 | Curated desktop apps, rich metadata, verified publishers |
| **AppImage** | ~2,500–3,000 | Decentralized across catalogs + GitHub |
| **Snap** ([Snap Store](https://snapcraft.io/store)) | ~6,400 | Mixed desktop/server/IoT, Ubuntu-centric |

### AppImage Catalogs

| Source | Apps | URL | Notes |
|--------|------|-----|-------|
| [Portable Linux Apps](https://portable-linux-apps.github.io/) | ~2,455 | Largest single AppImage catalog | Maintained by ivan-hc, tied to [AM package manager](https://github.com/ivan-hc/AM) |
| [AppImageHub](https://www.appimagehub.com/) | ~1,569 | Community hub | Powered by Opendesktop/Pling infrastructure |
| [Official AppImage catalog](https://appimage.github.io/apps/) | ~1,475 | GitHub-based, auto-scans releases | Structured data at [appimage.github.io](https://github.com/AppImage/appimage.github.io) repo |
| [GitHub `appimage` topic](https://github.com/topics/appimage) | 719+ repos | Many more ship .AppImage without the tag | |

Note: AppImageHub (appimagehub.com, ~1,569 apps) and the Official AppImage catalog (appimage.github.io, ~1,475 apps) are **separate sources** with significant overlap. The Official catalog is GitHub-hosted with structured JSON data, making it more suitable for automated ingestion. AppImageHub is a web portal with different metadata.

### GitHub as Source

GitHub is the single largest distribution channel for Linux desktop apps:

| Topic | Repos |
|-------|-------|
| [`linux`](https://github.com/topics/linux) | 65,950 |
| [`qt`](https://github.com/topics/qt) | 9,540 |
| [`desktop-app`](https://github.com/topics/desktop-app) | 6,531 |
| [`electron-app`](https://github.com/topics/electron-app) | 3,110 |
| [`gnome`](https://github.com/topics/gnome) | 2,431 |
| [`gtk`](https://github.com/topics/gtk) | 2,307 |
| [`kde`](https://github.com/topics/kde) | 1,116 |
| [`linux-app`](https://github.com/topics/linux-app) | 1,097 |
| [`linux-desktop`](https://github.com/topics/linux-desktop) | 848 |
| [`appimage`](https://github.com/topics/appimage) | 719 |
| [`flatpak`](https://github.com/topics/flatpak) | 682 |

Estimated **3,000–5,000 distinct Linux desktop apps** on GitHub, up to 10,000–20,000+ including cross-platform Electron/Tauri/Qt/GTK apps shipping Linux builds.

### Filtering SDKs vs End-User Apps

Raw GitHub topic counts include frameworks, SDKs, and dev tools (e.g. Tauri, electron-builder, GTK bindings). These are not installable end-user apps.

**Strongest triage signals:**
- **Ships binary releases** in `.AppImage`, `.flatpak`, `.deb`, `.snap` → end-user app
- **Listed on Flathub or AppImageHub** → already curated as end-user
- **Has a `.desktop` file** in the repo → designed for desktop end-user use

**Exclusion signals:**
- Topics: `framework`, `sdk`, `toolkit`, `library`, `cli`, `developer-tools`, `build-tool`
- Publishes to package registries (npm, crates.io, PyPI) instead of shipping binaries
- Description contains "a framework for...", "a library for..."

**Inclusion signals:**
- Topics: `app`, `desktop-app`, `music-player`, `editor`, `game`, `browser`
- Description contains "a music player", "a text editor", "an image viewer"
- Has GitHub Releases with `.AppImage` assets

### Provenance Model & Package Ownership

All apps on the relay must have clear provenance and unambiguous ownership. This is a **pre-launch requirement**, not a deferred question.

**Two tiers:**

**Tier 1 — Developer-published (verified):**
- Published by the app's own developer via zsp
- Developer signs events with their own Nostr key
- The Application event (kind 32267) `d` tag matches the developer's claimed package ID
- Highest trust — this is the canonical source

**Tier 2 — Indexer-published (mirrored):**
- Published by the zapstore team's indexer bot from curated catalog data
- Signed by a **dedicated indexer Nostr key** (e.g. `npub1zapstore-indexer...`)
- Events must include a `source` tag indicating provenance (e.g. `["source", "appimage.github.io"]`, `["source", "portable-linux-apps"]`)
- The client displays these differently (e.g. "Indexed from AppImageHub" vs verified publisher badge)

**Package ownership enforcement (anti-squatting):**

The zapstore team maintains a **curation set** (NIP-51 kind 30267) that maps each package `d` tag to its canonical pubkey. This is the source of truth for ownership.

- **Indexer-published apps**: The curation set maps the `d` tag to the indexer pubkey. Only the indexer can publish events for these IDs.
- **Developer claims**: When a developer wants to claim their app, they contact the zapstore team. The team verifies ownership (e.g. developer proves they control the upstream repo), then updates the curation set to point the `d` tag to the developer's pubkey. The indexer's events for that app are then deprioritized/removed.
- **Relay enforcement**: The zapstore relay checks incoming NIP-82 events against the curation set. If a `d` tag is already assigned to a pubkey, the relay rejects events from other pubkeys for that same `d` tag. This prevents squatting — you can't publish an Application event for a package ID you don't own.
- **New apps (unclaimed `d` tags)**: First publisher wins, subject to zapstore team review. The curation set is updated to record the assignment.

This mirrors how the Android zapstore already works — the curation set is the authority.

**Canonical package ID policy (`d` tag):**

AppImages don't have a standard package ID like Android's `com.example.app`. We define a convention:

- **Tier 1 (developer-published)**: Developer chooses their package ID. Recommended format is reverse-domain (e.g. `net.gossipnostr.gossip`), matching Flathub convention where available. Developer owns this ID via the curation set.
- **Tier 2 (indexer-published)**: The indexer assigns an ID based on the upstream source, using a stable identifier that survives repo renames and forks:
  - If the app has a Flathub ID, use it (e.g. `xyz.armcord.ArmCord`) — these are already globally unique
  - Otherwise, use the catalog's own identifier (e.g. AppImageHub slug or Portable Linux Apps name)
  - **Do not** derive the ID from the GitHub `owner/repo` alone — repos get renamed, transferred, and forked. The ID must be stable.
- **Dedup rule**: Two catalog entries are the same app if they point to the same upstream source URL (after normalization) OR share the same Flathub ID. The indexer maintains a mapping table of `(catalog_source, catalog_id) → canonical_d_tag` to catch renames and moves.
- **Fork handling**: Forks are treated as separate apps with separate `d` tags. If a fork becomes the de facto successor (upstream is abandoned), the zapstore team can reassign the canonical `d` tag via the curation set.

### Trust & Security for Indexed Apps

Auto-ingested binaries from catalogs carry risk. Mitigation:

**Before ingestion:**
- Only ingest from curated sources (AppImageHub, Portable Linux Apps) — these have existing community moderation
- Download the binary and compute SHA-256 hash ourselves (this becomes the `x` tag in the SoftwareAsset event)
- If the catalog provides a hash, verify it matches our computed hash. If it doesn't, reject the entry.
- If the catalog provides **no hash metadata** (common for AppImageHub entries), we still ingest — but our computed hash becomes the sole verification anchor. The client will verify downloads against this hash.
- If a catalog URL is **mutable** (no versioned URL, content could change), re-download and re-hash on each indexer run. If the hash changes, publish a new Release event pointing to a new SoftwareAsset. Do not silently update the existing event's hash.
- Check binary is a valid AppImage (ELF header + AppImage magic bytes). Reject anything that isn't.
- **No arbitrary GitHub ingestion.** The GitHub scanner (Phase 4) discovers *candidates* only. A human reviews candidates before the indexer publishes events. Nothing from the scanner goes live automatically.

**Trust tiers in the client (two tiers only):**
- **Verified publisher**: Developer published via zsp with their own key → full trust, shown normally
- **Curated index**: Indexed from a known catalog source by the zapstore team → shown with "Indexed from [source]" label

There is no "unreviewed" tier. Every app on the relay is either developer-published or team-reviewed. The GitHub scanner feeds a review queue, not the relay.

**Blocklist/moderation:**
- Maintain a blocklist of known-bad package IDs (can be a NIP-51 list)
- The zapstore relay can refuse to serve events for blocklisted packages
- Community reporting: users can flag apps (future — NIP-56 reports)

**What we explicitly do NOT do:**
- We do not run or sandbox AppImages server-side (too expensive, too complex)
- We do not guarantee safety of any binary — same as Android side, trust is delegated to publisher identity and community signals (zaps, reviews, web of trust)

### Indexer

The indexer is a **standalone service** operated by the zapstore team (separate from zsp, which is for developers). It:
- Runs on a schedule (daily for catalog re-scrape, hourly for GitHub release checks)
- Signs events with a dedicated indexer Nostr key
- Publishes to the zapstore relay
- Logs all ingestion for audit/debugging

---

## Pillar 2: Publishing via zsp

zsp is a **Go CLI tool** ([zapstore/zsp](https://github.com/zapstore/zsp)) that currently supports Android APK publishing only. It handles the full workflow: fetch release → parse binary → build NIP-82 events → sign → upload to blossom → publish to relays.

### Current Architecture (Android-only)

Key modules in `zsp/internal/`:

| Module | Purpose | Linux Impact |
|--------|---------|-------------|
| `apk/` | APK parsing, metadata extraction, cert verification | Need equivalent for AppImage |
| `nostr/events.go` | Builds kind 32267/30063/3063 events | Extend with Linux platform IDs + MIME types |
| `nostr/signer.go` | Event signing (privkey, NIP-46, NIP-07) | No change needed |
| `workflow/workflow.go` | Orchestrates fetch → build → sign → upload → publish | Generalize for multi-format |
| `picker/picker.go` | ML-based APK variant selection (KNN model) | Need AppImage file filter |
| `source/` | Release fetching from GitHub, GitLab, Gitea, F-Droid, web | Extend to recognize `.AppImage` assets |
| `config/` | YAML config parsing + wizard | Add Linux-specific config fields |
| `blossom/` | File upload to blossom servers | No change needed |

### What Needs to Change

**1. New module: `internal/appimage/parser.go`**
- Parse AppImage metadata: extract app name, version from embedded `.desktop` file
- Read ELF headers to determine architecture (x86_64, aarch64)
- Extract icon from AppImage (via `--appimage-extract *.desktop *.png` or by mounting)
- No certificate hash equivalent — AppImages are unsigned by default. The developer's Nostr pubkey serves as the identity anchor instead (same approach as `LinuxPackageManager` in the client)

**2. Extend `internal/nostr/events.go`**
- Add `linux-x86_64` to platform identifiers (alongside existing `android-*` mappings). `linux-aarch64` deferred until the client supports and tests it — do not publish assets with no defined consumer.
- Set MIME type to `application/vnd.appimage` for AppImage assets
- Make `apk_certificate_hash` tag conditional (Android-only) — AppImage assets skip this tag
- The existing `AssetMetadata` struct already has all needed fields (`SHA256`, `Size`, `URLs`, `Platforms`, `Filename`); just need to not require Android-specific fields

**3. Extend `internal/picker/picker.go`**
- Add `FilterAppImages()` alongside existing `FilterAPKs()`
- Simpler than APK selection — usually only one AppImage per release (no ABI splits)
- Filter by `.AppImage` extension in release asset filenames

**4. Extend `internal/source/*.go`**
- GitHub, GitLab, Gitea source adapters already fetch all release assets
- Just need to recognize `.AppImage` files in addition to `.apk`
- F-Droid source adapter is Android-only — skip for Linux

**5. Extend `internal/config/config.go`**
- Add `format` field to app config (default: `apk`, option: `appimage`)
- Or auto-detect from release assets

**6. Extend `internal/workflow/workflow.go`**
- Branch on format: APK path (existing) vs AppImage path (new)
- AppImage path skips APK-specific steps (cert extraction, ABI detection, min/target SDK)
- The existing multi-asset model (`SoftwareAssets: []*nostr.Event`) already supports this

### Estimated Scope

The zsp codebase is well-structured for extension. Key insight: the event model is already format-agnostic — `AssetMetadata` has fields for hash, size, URLs, platforms, filename. The Android-specific parts (cert hash, SDK versions, ABI splits) are additive, not structural. Adding AppImage support is roughly:

- **1 new file**: `internal/appimage/parser.go` (~150–200 lines)
- **5–6 file edits**: events.go, picker.go, workflow.go, config.go, source adapters
- **No changes** to signing, blossom upload, relay publishing, or UI

---

## Pillar 3: Desktop Client

The detailed client implementation plan is in [issue #334](https://github.com/zapstore/zapstore/issues/334). Summary:

**Scope: ~1 new file + ~5 small edits.** The existing codebase architecture (abstract PackageManager, cross-platform UI, NIP-82 relay queries with `#f` tag filtering) means the Linux port is not a rewrite.

### Key file: `LinuxPackageManager`

Implements 6 abstract methods from PackageManager:
- `platform` → `linux-x86_64`
- `packageExtension` → `.AppImage`
- `install()` → verify hash, atomic copy + chmod +x, create `.desktop` entry
- `uninstall()` → delete AppImage + `.desktop` entry
- `launchApp()` → `Process.start` detached
- `syncInstalledPackages()` → verify AppImages still exist on disk

### Known Gotchas
- **FUSE dependency**: Type 2 AppImages need `libfuse2` (not default on Ubuntu 22.04+). Detect at launch, show actionable error.
- **No Amber on Linux**: Nostr signing needs nsec file or NIP-46 remote signer (later phase)
- **libsecret dependency**: `flutter_secure_storage_linux` needs it (standard on GNOME, not minimal distros). Check at startup.
- **Testing surface**: Need at least Ubuntu, Fedora, Arch to cover major distro families

### Testing Strategy

**CI (automated, every PR):**
- `flutter build linux` on GitHub Actions Ubuntu runner — build verification
- Unit tests for `LinuxPackageManager` methods (hash verification, path construction, `.desktop` file generation)

**E2E test matrix (manual or scripted, per release):**

| Test | Ubuntu LTS | Fedora | Arch |
|------|-----------|--------|------|
| Build + launch client | | | |
| Download AppImage from relay | | | |
| Hash verification (valid) | | | |
| Hash verification (mismatch → reject) | | | |
| Install (atomic copy + chmod + .desktop) | | | |
| Verify .desktop entry appears in app menu | | | |
| Launch installed AppImage | | | |
| Uninstall (remove files + .desktop) | | | |
| Verify uninstall cleans up completely | | | |
| Restart client → syncInstalledPackages detects installed apps | | | |
| Missing FUSE → clear error message | | | |
| Missing libsecret → clear error message | | | |

**Rollback behavior:**
- If install fails mid-copy: temp file is deleted, no partial install left behind (atomic install via `.tmp` + rename)
- If hash mismatch: download is rejected, user sees clear error, no file persisted
- If launch fails (FUSE missing, wrong arch): error caught, user gets actionable message

---

## Beachhead Market

**Hypothesis**: There is significant overlap between nostr enthusiast/dev communities and Linux users. The beachhead market, albeit small, is there and awaiting zapstore.

Supporting data:
- Flathub: 433M downloads in 2025, 1M+ active users, 20% YoY growth ([source](https://flathub.org/en/year-in-review/2025))
- Steam Deck (SteamOS/Linux) driving mainstream Linux desktop adoption
- Linux desktop audience skews developer/enthusiast — the nostr demographic
- Many nostr tools are already Linux-first (relay implementations, CLI tools, etc.)

---

## Phased Rollout

### Guiding principle: build supply before demand

An empty app store kills adoption permanently. If a user downloads zapstore for Linux and sees 3 apps, they uninstall and never come back. **The catalog must be populated before or simultaneously with the client launch.** Supply first, demand second.

### Phase 1 — Vertical Slice: One App, End-to-End (days)

Prove the pipeline works before scaling anything.

- [ ] Add `internal/appimage/parser.go` to zsp
- [ ] Extend zsp event creation with `linux-x86_64` platform + `application/vnd.appimage` MIME
- [ ] Extend zsp picker + source adapters to recognize `.AppImage` assets
- [ ] Extend zsp workflow to branch on format (APK vs AppImage)
- [ ] **Publish zapstore itself as an AppImage** via zsp — dogfood the pipeline
- [ ] Implement `LinuxPackageManager` in the client ([#334](https://github.com/zapstore/zapstore/issues/334))
- [ ] Fix hardcoded platform strings in the client
- [ ] `flutter build linux` → download zapstore's own AppImage from relay → install → launch
- **Success metric**: zapstore published via zsp, installable via itself on Linux. Full round-trip works.
- **Result**: Validated pipeline. Zapstore is its own first Linux app.

### Phase 2 — Seed Catalog: Nostr Apps + Top AppImages (weeks)

Build the launch catalog. **Curate for the audience, don't spray-and-pray.**

**Tier 1 — Nostr developer outreach (highest value):**
- [ ] Identify nostr desktop apps that already ship Linux builds on GitHub (e.g. [Gossip](https://github.com/mikedilger/gossip), and others — survey needed)
- [ ] Reach out to developers, help them publish via zsp with their own Nostr key
- [ ] These are Tier 1 (verified publisher) — best trust signal, most relevant to the audience
- **Target**: 10–20 nostr apps published by their own developers

**Tier 2 — Curated high-signal AppImages (indexer):**
- [ ] Build the standalone indexer service
- [ ] Hand-pick the top ~100 most-downloaded/most-starred apps from [AppImageHub](https://www.appimagehub.com/) and [Portable Linux Apps](https://portable-linux-apps.github.io/)
- [ ] Verify each is downloadable, valid AppImage, and actually launches
- [ ] Publish as Tier 2 events with `source` provenance tags
- [ ] Cross-reference: top 50 most-starred GitHub repos that ship `.AppImage` in releases (e.g. [rustdesk](https://github.com/rustdesk/rustdesk), [beekeeper-studio](https://github.com/beekeeper-studio/beekeeper-studio), [moonlight-qt](https://github.com/moonlight-stream/moonlight-qt))
- **Target**: ~100–150 verified, high-quality AppImages

**Combined launch catalog: ~120–170 apps.** Small but high-quality, relevant to the audience, all verified-installable.

- **Success metric**: every app in the catalog can be downloaded, hash-verified, and launched on Ubuntu LTS. Not raw count — install success rate.
- **Result**: Users open zapstore on Linux and see apps they recognize and want.

### Phase 3 — Broader Catalog + Polish (months)

Scale the catalog and harden the experience.

- [ ] Automated ingestion from Official AppImage catalog (appimage.github.io, ~1,475 entries)
- [ ] Dedup per canonical ID policy (Flathub ID > catalog ID > upstream source URL; see Provenance section)
- [ ] Filter broken download URLs, dead projects, non-functional AppImages
- [ ] Linux signing story (nsec file or NIP-46 remote signer)
- [ ] Polish UX: provenance labels in client, icon extraction, Linux-specific copy
- [ ] Developer claim flow: developer publishes own events → takes precedence over indexer
- **Target**: 500–800 verified-downloadable apps
- **Success metric**: catalog growth rate, install success rate, developer claim rate
- **Result**: Respectable catalog, growing organically

### Phase 4 — Ecosystem Growth

- [ ] Background update checking (detect new releases for installed apps)
- [ ] System notifications (freedesktop D-Bus)
- [ ] GitHub scanner for `appimage`-tagged repos (719 repos — discovers candidates for human review, not auto-ingested; nice-to-have, not critical path)
- [ ] Flatpak support (secondary format in zsp + client)
- [ ] Community-driven app submissions

---

## What's Explicitly Deferred

- **GitHub scanner at scale**: The 719 `appimage`-tagged repos are a manageable number, but the scanner adds complexity (rate limits, false positives, stale repos). The real catalog will come from developer self-publishing (Tier 1) and curated ingestion (Tier 2). Scanner is Phase 4 nice-to-have — it discovers candidates for human review, nothing goes live automatically.
- **linux-aarch64**: The zapstore client only targets x86_64 for now. We won't publish ARM64 AppImages in zsp until the client can actually install them on ARM64 Linux.
- **Flathub cross-reference**: Enriching metadata from Flathub is premature optimization. GitHub READMEs + AppImage catalog data is enough for launch.
- **Flatpak as a format**: Different install model (requires Flatpak daemon). Out of scope until AppImage is fully working.

---

## Nostr Apps with Linux Builds (Tier 1 Seed Catalog Candidates)

Survey of nostr apps already shipping Linux desktop builds — these are the highest-value targets for Phase 2 developer outreach.

### GUI Desktop Apps

| App | Repo | Stars | Linux Formats | Notes |
|-----|------|-------|--------------|-------|
| **Gossip** | [mikedilger/gossip](https://github.com/mikedilger/gossip) | 850 | **AppImage**, Flatpak, .deb | Rust nostr client, most mature Linux support |
| **Iris** | [irislib/iris-messenger](https://github.com/irislib/iris-messenger) | 728 | **AppImage**, .deb | Nostr client |
| **Notedeck** | [damus-io/notedeck](https://github.com/damus-io/notedeck) | 288 | .deb (Intel+ARM), .rpm (Intel+ARM) | Damus desktop client, beta. Binaries at [damus.io/notedeck/install](https://damus.io/notedeck/install/) |
| **Alby Hub** | [getAlby/hub](https://github.com/getAlby/hub) | 249 | tar.bz2 (desktop + server) | Lightning node manager |
| **Coop** | [lumehq/coop](https://github.com/lumehq/coop) | 177 | tar.gz, Snap, Flatpak | Nostr chat (by Lume team) |
| **nostr-relay-tray** | [CodyTseng/nostr-relay-tray](https://github.com/CodyTseng/nostr-relay-tray) | ~165 | Linux binary | Desktop personal relay |
| **Nostrmo** | [haorendashu/nostrmo](https://github.com/haorendashu/nostrmo) | 112 | **AppImage** | Flutter nostr client |
| **Nostrid** | [lapulpeta/Nostrid](https://github.com/lapulpeta/Nostrid) | ~89 | Linux binary (.NET) | Multi-platform client |
| **Futr** | [futrnostr/futr](https://github.com/futrnostr/futr) | ~65 | **AppImage**, Flatpak | Haskell+Qt5 client. Also on [AppImageHub](https://www.appimagehub.com/p/1876377) |
| **Pretty Good** | [wds4/pretty-good](https://github.com/wds4/pretty-good) | ~35 | **AppImage** | Web of trust client |
| **NoorNote** | [noornote.app](https://noornote.app/download/) | new | .deb, .rpm | Nostr client for Linux+macOS |
| **OstrichGram** | [OstrichGram/OstrichGram](https://github.com/OstrichGram/OstrichGram) | ~20 | Linux binary | Telegram-style nostr chat |

### CLI / Server Tools (secondary)

| App | Repo | Stars | Notes |
|-----|------|-------|-------|
| **nak** | [fiatjaf/nak](https://github.com/fiatjaf/nak) | 343 | Nostr CLI swiss-army knife |
| **Mostro** | [MostroP2P/mostro](https://github.com/MostroP2P/mostro) | 276 | Lightning P2P exchange daemon |

### No Linux Builds

Lume (Mac/Win only), Damus (iOS only), Amethyst (Android), Zeus/Amber (Android), Coracle/Snort/Nostrudel (web only).

### Summary

**~12 nostr GUI desktop apps with Linux builds exist today. 5 ship AppImage specifically** (Gossip, Iris, Nostrmo, Futr, Pretty Good). The Phase 2 target of 10–20 Tier 1 nostr apps is achievable — the supply exists, it just needs to be published as NIP-82 events.

---

## Open Questions

1. **Cross-platform releases**: When an app has both APK and AppImage, should they share the same Application event (kind 32267) with multiple `f` tags, or be separate events? NIP-82 supports multiple `f` tags per Application.
