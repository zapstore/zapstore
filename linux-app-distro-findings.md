# Linux App Distribution — Community Research Findings

Research conducted Feb 2026. Sources: Reddit, Hacker News, GitHub issues, Linux forums, Snapcraft forum, Flathub Discourse, AppImage Discourse, tech blogs, academic papers.

---

## The Big Picture

Linux app distribution is dominated by three universal formats (Flatpak, Snap, AppImage) plus traditional distro packages. The community is exhausted by the fragmentation. Linus Torvalds called shipping Linux binaries "a major f***ing pain in the a**" ([DebConf 2014](https://news.softpedia.com/news/Linus-Torvalds-Says-Linux-Binary-Packages-Are-Terrible-Valve-Might-Save-the-Desktop-458087.shtml), [It's FOSS](https://itsfoss.com/desktop-linux-torvalds/)). The irony: the "solution" to packaging fragmentation created its own fragmentation.

XDA Developers: ["Flatpak and Snap aren't just formats; they are ecosystems with governance expectations, publishing pipelines, and different answers to who should control the storefront."](https://www.xda-developers.com/linuxs-app-problem-app-stores-refuse-merge/)

---

## What Users Hate Most

### 1. Trust & Verification is Broken Everywhere

This is the #1 theme across every project, forum, and blog post.

**Snap Store — active malware problem:**
- Fake crypto wallet apps stole ~$490K (9 BTC) from one user ([Alan Pope: Exodus Bitcoin Wallet $490K Swindle](https://popey.com/blog/2024/02/exodus-bitcoin-wallet-490k-swindle/), [Snapcraft Forum report](https://forum.snapcraft.io/t/report-of-fake-crypto-wallet-exodus-snap-s/49161), [GamingOnLinux](https://www.gamingonlinux.com/2024/02/snap-store-from-canonical-ubuntu-hit-with-another-crypto-scam-app/))
- Jan 2026: attackers hijacked expired publisher domains to push malware through previously trusted apps ([Alan Pope](https://blog.popey.com/2026/01/malware-purveyors-taking-over-published-snap-email-domains/), [Help Net Security](https://www.helpnetsecurity.com/2026/01/21/linux-malware-snap-store/))
- Canonical temporarily restricted all new snap registrations to manual review ([Bitdefender](https://www.bitdefender.com/en-us/blog/hotforsecurity/canonical-changes-snap-store-policy-in-ubuntu-after-criminals-upload-fake-crypto-apps), [The Register](https://www.theregister.com/2024/03/28/canonical_snap_store_scams/), [Phoronix](https://www.phoronix.com/news/Snap-Store-Malicious-Apps))
- All apps display a "Safe" badge regardless — false confidence ([It's FOSS](https://itsfoss.com/news/snap-store-under-siege/))

**Flathub — verification gap:**
- Unverified apps now show a red "UNVERIFIED" warning, alienating legitimate community maintainers ([Medium: The Flatpak Verification Problem](https://codemonkeymike.medium.com/the-flatpak-verification-problem-93fb1dc9cedb))
- Major apps (Chrome, VLC, Spotify, Zoom, Signal, Blender, Inkscape) will likely never be "verified" because upstream devs don't publish to Flathub
- Verification is app-wide, not per-build — multiple unknown contributors with write access can push "verified" versions ([flathub/flathub #4855](https://github.com/flathub/flathub/issues/4855))
- Linux Mint 22 hides unverified apps by default, making popular software invisible to new users
- Feb 2025: Fedora Project Leader publicly questioned Flathub's review process, sparking a major controversy ([GNOME Blog response](https://blogs.gnome.org/alatiera/2025/02/19/the-fedora-project-leader-is-willfully-ignorant-about-flathub/))

**AppImage — zero trust infrastructure:**
- No signing, no verification, no checksums in the ecosystem
- AM project manages 2,500+ AppImages with no integrity validation ([ivan-hc/AM #2104](https://github.com/ivan-hc/AM/issues/2104))
- Fake AppImages on AppImageHub distributing cracked software with malware
- Signing has been requested since 2016 ([AppImageKit #238](https://github.com/AppImage/AppImageKit/issues/238)), still unresolved
- No chain of trust exists — PGP signing exists but no way to define which keys are trustworthy

**Community quote:** ["With flatpaks, your study of source code is useless as there is no way to verify that a bad maintainer didn't do something bad, and the only thing you can be sure of is digital signature or knowing it comes from a trusted source."](https://forums.linuxmint.com/viewtopic.php?t=457037) — Linux Mint Forums

### 2. Centralization / Corporate Control

**Snap Store:**
- Backend is proprietary and closed-source — only Canonical controls it
- No third-party Snap repositories allowed
- Mark Shuttleworth's defense: the open source store ["attracted precisely no users and no patches"](https://forum.snapcraft.io/t/please-address-store-is-not-open-source-again/18442)
- Linux Mint blocked Snap entirely after Canonical silently replaced `apt install chromium` with a snap shim — installing snapd without user consent ([LWN](https://lwn.net/Articles/825005/), [Hackaday](https://hackaday.com/2020/06/24/whats-the-deal-with-snap-packages/))
- Clem Lefebvre (Mint founder): ["This is in effect similar to a commercial proprietary solution, but with two major differences: It runs as root, and it installs itself without asking you."](https://www.silicon.co.uk/workspace/linux-mint-pulls-support-for-canonicals-snap-345596/amp)
- Community: ["Snap sucks. The way open source is straying further and further from its principles."](https://news.ycombinator.com/item?id=24383341) — top HN comment

**Flathub:**
- Technically federated (anyone can host a Flatpak remote), practically a single point of failure
- Moving toward paid apps via Stripe/Flathub LLC — raises governance concerns
- Red Hat (RHEL 10) offloads desktop packages to Flathub without funding the volunteer infrastructure
- Siosm's blog: the goal is to avoid ["replicating the predatory model of other app stores"](https://tim.siosm.fr/blog/2025/11/24/building-better-app-store-flathub/)

### 3. Performance

- Snap cold-start: Firefox ~11 seconds vs <1 second for Flatpak version ([Snap vs Flatpak Guide 2025](https://www.glukhov.org/post/2025/12/snap-vs-flatpack/))
- snapd: 100% CPU on boot reported on Ubuntu 24.04 ([VirtVPS](https://www.virtvps.com/fixing-snapds-frustrating-100-cpu-usage-on/)), 90MB+ memory over time ([Ubuntu Discourse](https://discourse.ubuntu.com/t/snapd-high-memory-usage-after-snap-install-some-app/35327))
- GNOME Software: 30-60 minute loading loops on Fedora ([GitLab #2822](https://gitlab.gnome.org/GNOME/gnome-software/-/issues/2822)), can only install one flatpak at a time ([Fedora Discussion](https://discussion.fedoraproject.org/t/gnome-software-is-very-slow/144773))
- Old Pop!_Shop: universally despised — crashes, freezes, "completely broken" ([GitHub issues](https://github.com/pop-os/shop/issues))

### 4. Disk Space / Bloat

- Flatpak: 75x more storage than RPM for the same app — runtime (1.6GB) + repo data (1.2GB) that cannot be removed ([guoyunhe/fuck-flatpak](https://github.com/guoyunhe/fuck-flatpak))
- Snap: keeps 3 revisions of each snap by default, loop mounts clutter `lsblk`/`df`/`fdisk`
- AppImage: each app bundles own libraries, no shared runtimes, no deduplication

### 5. Desktop Integration & Theming

- AppImages don't appear in app menus, no icons, no file associations. Moving an AppImage breaks its launcher.
- Flatpak sandbox prevents direct theme access — apps look alien. KDE apps ignore themes, fonts, icon settings.
- Snap theming requires installing each theme individually per-app
- AppImageLauncher (workaround) hasn't been updated since 2020

### 6. Updates

- AppImage: no standard update mechanism. AppImageUpdate is beta-level, incompatible with electron-builder ([AppImageUpdate #73](https://github.com/AppImageCommunity/AppImageUpdate/issues/73))
- Snap: forced auto-updates break workflows, can kill running processes. ["Ubuntu Snap auto updates broke my development setup and there is no way to turn them off."](http://raymii.org/s/blog/Ubuntu_Snap_auto_updates_broke_my_development_setup.html) — Raymii.org
- Flatpak: no built-in auto-update — leaves it to desktop environments (inconsistent). Users discover flatpaks haven't updated in months. ([Fedora Discussion](https://discussion.fedoraproject.org/t/flatpak-automatic-updates-where-how/106418))

### 7. AppImage FUSE Crisis

- Ubuntu 22.04+ dropped `libfuse2`, breaking most AppImages
- Ubuntu 24.04 renamed the package to `libfuse2t64`, adding confusion
- Hundreds of GitHub issues across projects (Zettlr, PrusaSlicer, Joplin, Cursor, Obsidian)
- Ubuntu 24.04's AppArmor policies also break Electron sandbox in AppImages
- Critics: "An application format incompatible with the latest version of its core dependency is fundamentally broken." — [ludditus.com](https://ludditus.com/2024/10/31/appimage/)
- Type-3 runtime in development to address this

### 8. Sandbox Security Theater

- Flatpak: many popular apps ship with `filesystem=host` or `filesystem=home` — GIMP, VSCode, PyCharm, Steam, Audacity, VLC. "All it takes to 'escape the sandbox' is `echo download_and_execute_evil >> ~/.bashrc`" — [flatkill.org](https://flatkill.org/)
- Audio permission gives microphone access automatically (PulseAudio, not PipeWire)
- AppImage: no sandboxing at all, runs with full user permissions
- Snap: better confinement (AppArmor) but auto-connect of interfaces can be overly permissive

### 9. Developer Packaging Burden

- ["If you do go down this route of packaging for Linux related systems, I suggest only dealing with one distro and abandoning the idea you will package for more."](https://dev.to/dyfet/distro-packaging-35do) — DEV Community
- Flatpak: if a dependency isn't in the runtime, developers must package everything themselves. No package manager within the sandbox. ([BrixIT Blog](https://blog.brixit.nl/developers-are-lazy-thus-flatpak/))
- Snap: GNOME SDK includes an 1101-line libadwaita patch for Ubuntu Yaru branding that breaks other distros' apps ([GeopJr Blog](https://geopjr.dev/blog/snap-the-good-the-bad-and-the-ugly))
- DuckStation developer publicly blocked Arch Linux packaging due to maintenance burden from distro packagers ([LavX News](https://news.lavx.hu/article/duckstation-developer-blocks-arch-linux-packaging-citing-license-violations-and-maintenance-burden))
- ["Packaging has a well-earned reputation for being cumbersome, thankless and finicky."](https://blogs.vmware.com/opensource/2022/08/30/of-builds-and-packaging/) — VMware OSS Blog

---

## What Users Love

| Format | Loved For |
|--------|-----------|
| **AppImage** | One file = one app. No install, no root, USB-portable, fastest startup, version coexistence. Linus Torvalds: "This is just very cool." |
| **Flatpak** | Cross-distro, fresh software, system stability, 435M downloads in 2025, sandboxing (in theory) |
| **Snap** | Easy to build (snapcraft CLI praised), strong for IoT/server, transactional updates |

---

## App Store UX Rankings

Based on community reviews, forum threads, and tech publications:

### 1. COSMIC Store (Pop!_OS 24.04) — Best Speed & Modern Design
- Rust-built, ultra fast. System76 CEO: ["I found it's more efficient to update via the app than command line."](https://blog.system76.com/post/your-monthly-cosmic-fix/)
- Clean modern interface, integrates System repos + Flathub ([The Register review](https://www.theregister.com/2025/12/22/popos_2404_cosmic_epoch_1/), [FOSS Force](https://fossforce.com/2025/12/pop_os-24-04s-new-scratch-built-cosmic-hands-on-with-screenshots/))
- Very new (stable Dec 2025), ecosystem still maturing
- Missing: verified badges ([#444](https://github.com/pop-os/cosmic-store/issues/444)), offline functionality ([#500](https://github.com/pop-os/cosmic-store/issues/500)), repo management UI ([#272](https://github.com/pop-os/cosmic-store/issues/272))

### 2. elementary OS AppCenter — Best Design Cohesion
- Pay-what-you-can model via Stripe Connect — unique in Linux ([elementary blog](https://blog.elementary.io/elementary-appcenter-flatpak/))
- Every curated app is human-reviewed for privacy and security ([Linux Journal](https://www.linuxjournal.com/content/elementary-os-8-where-privacy-meets-design-simplicity-better-linux-experience))
- Small catalog (133 curated apps), elementary-only
- ["Miles ahead of the competition"](https://thelinuxexp.com/elementary-OS7-horus-review/) — The Linux Experiment

### 3. Linux Mint Software Manager — Best for Beginners
- Designed explicitly for beginners. ~30,000 packages. ([FOSS Linux guide](https://www.fosslinux.com/103961/the-comprehensive-guide-to-using-the-linux-mint-software-manager.htm))
- Community ratings and reviews prominently displayed
- Reliable and "doesn't waste my time"
- Dated UI, limited discovery/sorting ([Linux Mint Forums](https://forums.linuxmint.com/viewtopic.php?t=432019))

### 4. Pamac (Manjaro) — Best All-in-One
- Supports Pacman + AUR + Flatpak + Snap from one interface ([MakeUseOf](https://www.makeuseof.com/pamac-manjaro-linux-guide/))
- "Blazing fast with installations"
- Looks like a package manager, not an app store — requires more technical knowledge ([Manjaro Forum](https://forum.manjaro.org/t/dedicated-app-store/101188))

### 5. KDE Discover — Fast but Limited
- ["REALLY fast and responsive"](https://linuxreviews.org/Plasma_Discover) — LinuxReviews
- Supports multiple sources including AppImages from store.kde.org
- Reviews system actively broken ([KDE bug #411034](https://bugs.kde.org/show_bug.cgi?id=411034)), apps listed Z-to-A by default, limited package coverage

### 6. Ubuntu App Center — Modern but Controversial
- Flutter-based, faster than predecessor ([OMG! Ubuntu](https://www.omgubuntu.co.uk/2023/09/ubuntu-app-center-app-arrives))
- Snap-only, no Flatpak support — deeply unpopular
- Cannot install local .deb files

### 7. GNOME Software — Broadest Adoption, Worst Performance
- Supports the widest range of formats
- Chronic slowness, loading loops, high memory usage ([GNOME Discourse](https://discourse.gnome.org/t/gnome-software-is-so-slow-it-always-refreshes-loading-etc/29291))
- ["GNOME Software is so slow it can only install one flatpak at a time and nothing else"](https://discussion.fedoraproject.org/t/gnome-software-is-very-slow/144773) — Fedora Discussion

---

## The Gaps Nobody Has Filled

### No Decentralized Linux App Store Exists
- AppImageKit issue #175 (P2P distribution via IPFS) has 82 comments over 8+ years, unresolved
- SkyDroid (Android, Flutter-based, Sia Skynet) is the closest concept
- Lemmy proposals for federated app stores exist but no implementation
- IEEE paper (2020) proposed blockchain-based decentralized app store — academic only
- Community question: "How would users verify that apps hadn't been tampered with in a federated app store?"

### No Paid App Store for Linux
- Alan Pope (Sep 2023): ["There is still no Linux app store"](https://blog.popey.com/2023/09/there-is-still-no-linux-app-store/) — you cannot click "Buy" anywhere
- Flathub's Stripe integration is nascent, not widely adopted ([Flathub LLC proposal](https://github.com/PlaintextGroup/oss-virtual-incubator/blob/main/proposals/flathub-linux-app-store.md))
- Snap Store has no purchase capability
- elementary AppCenter's pay-what-you-can is the closest, but elementary-only

### Every Multi-Format Store Project is Dead or Broken
- [AppOutlet](https://github.com/AppOutlet/AppOutlet): archived Feb 2026
- [linuxappstore](https://github.com/linuxappstore/linuxappstore): dead since 2019
- [bauh](https://github.com/vinifmor/bauh): barely functional, Python 3.14 broke it ([#406](https://github.com/vinifmor/bauh/issues/406))
- [GitHub-Store](https://github.com/rainxchzed/Github-Store): 5,500 stars but no Linux format support yet ([#208](https://github.com/rainxchzed/Github-Store/issues/208))

### Ratings & Reviews Don't Work Anywhere
- KDE Discover: review system actively broken ([KDE bug #411034](https://bugs.kde.org/show_bug.cgi?id=411034), [#503653](https://bugs.kde.org/show_bug.cgi?id=503653))
- Snap Store: doesn't show reviews to publishers ([snapcraft.io #2248](https://github.com/canonical/snapcraft.io/issues/2248))
- ODRS: stale reviews, sparse coverage
- No store has reputation-based sorting

### No Per-Build Verification
- Flathub verification is app-wide — any contributor with write access can push a "verified" build ([flathub #4855](https://github.com/flathub/flathub/issues/4855))
- No store verifies individual builds cryptographically

### No Enterprise Management
- Whitelist/blacklist requested in COSMIC Store ([#427](https://github.com/pop-os/cosmic-store/issues/427), [#426](https://github.com/pop-os/cosmic-store/issues/426)) but doesn't exist anywhere

---

## Community Wish List (Synthesized)

| Desire | Current State |
|--------|--------------|
| Decentralized, federated repos | Snap is locked down; Flathub is de facto centralized |
| Fully open-source infrastructure | Snap Store backend is proprietary |
| User control over updates | Snap forces; Flatpak inconsistent; AppImage has none |
| Real publisher identity verification | Snap "Safe" badge is meaningless; Flathub verification rate is low |
| Developer publishes once, works everywhere | Must target multiple formats, build pipelines, support channels |
| GUI-first, no terminal required | Software center UX varies wildly by distro |
| Privacy-respecting | Snap Store collects personal data |
| Reproducible/auditable builds | Only Nix/Guix offer this (steep learning curve) |
| Direct developer support/payments | No store has working payments |

---

## Zapstore Alignment

The landscape has openings that map directly to zapstore's architecture:

| Community Pain | Zapstore Answer |
|----------------|-----------------|
| Centralized control (Snap/Flathub) | Nostr relays — decentralized by protocol |
| Broken trust/verification | NIP-82 provenance: developer npub signs events, SHA-256 hash in SoftwareAsset |
| No decentralized app store exists | Nostr client-relay architecture — no single server controls distribution |
| Proprietary store backends | Open protocol, no proprietary server |
| No paid app infrastructure | Zaps — native Bitcoin/Lightning |
| Corporate gatekeeper | Web of trust, community curation via NIP-51 |
| Privacy concerns | No accounts, no tracking, pseudonymous identity |
| Per-build verification missing | Every SoftwareAsset event contains the file hash, signed by publisher |

The biggest risk flagged by the community for any decentralized store — "How would users verify that apps hadn't been tampered with?" — is what NIP-82's SHA-256 hash verification + developer npub signing addresses. Every SoftwareAsset event is cryptographically signed by the publisher and contains the file's hash, verifiable by any client without trusting the relay.

---

## Linus Torvalds on Linux App Distribution

Torvalds has been one of the most vocal critics of Linux's app distribution story. His views are important because he speaks from direct experience — he ships [Subsurface](https://subsurface-divelog.org/) (an open-source dive log app) and adopted AppImage for Linux distribution after finding traditional packaging impossible.

### The Core Rant (DebConf 2014)

[Video](https://www.youtube.com/watch?v=5PmHRSeA2c8) (~5:40 mark)

> "We make binaries for Windows and OS X. We basically don't make binaries for Linux. Why? Because binaries for Linux desktop applications is a major f***ing pain in the ass."

> "You don't make binaries for Linux. You make binaries for Fedora 19, Fedora 20, maybe there's even like RHEL 5 from ten years ago."

> "So you actually want to just compile one binary and have it work. Preferably forever. And preferably across all Linux distributions."

> "And I actually think distributions have done a horribly, horribly bad job."

### AppImage Endorsement (Google+, Nov 2015)

> "I finally got around to play with the 'AppImage' version of Subsurface, and it really does seem to 'just work'. This is just very cool."

Subsurface adopted AppImage as its Linux distribution format. Torvalds put his money where his mouth was.

### Shared Libraries Are Harmful (LKML, May 2021)

[LWN](https://lwn.net/Articles/855464/)

> "Shared libraries are not a good thing in general. They add a lot of overhead, but more importantly they also add lots of unnecessary dependencies and complexity, and almost no shared libraries are actually version-safe."

> "Pretty much the only case shared libraries really make sense is for truly standardized system libraries that are everywhere, and are part of the base distro."

### Market-Driven Standardization via Valve (LinuxCon Europe 2013)

[Linux Foundation Blog](https://www.linuxfoundation.org/blog/blog/10-best-quotes-from-linus-torvalds-keynote-at-linuxcon-europe)

> "It's the best model for standardization. Standards should not be people sitting in a smoky room and writing papers. It's being successful enough to drive the market."

He predicted Valve/Steam would force the Linux ecosystem to standardize. The Steam Deck has largely validated this.

### The Kernel Rule Distros Break (LKML, Dec 2012)

[LKML](https://lkml.org/lkml/2012/12/23/75)

> "WE DO NOT BREAK USERSPACE! If a change results in user programs breaking, it's a bug in the kernel. We never EVER blame the user programs."

The bitter irony: the kernel maintains strict ABI stability, but glibc updates, library version changes, and distro packaging routinely break apps.

### What a "Torvalds-Approved" System Looks Like

Distilled from all his statements:

1. **Self-contained binaries** — apps bundle their own dependencies (like AppImage, like Windows/macOS apps)
2. **One binary, works forever, works everywhere** — compile once, run on every distro
3. **Base OS as stable platform** — like Android, apps sit on top without caring about system specifics
4. **Market-driven adoption** — a dominant player forces convergence, not committees
5. **Shared libraries only for universal base** — libc and similar; everything else bundled
6. **Don't break userspace** extended to the full stack — not just the kernel

AppImage is the closest to this vision (which is why he endorsed it). Zapstore's AppImage-first approach on Linux aligns with every property on this list.

---

## Personas

### 1. The App Developer — "I just want to ship my app"

**Profile:** Builds a desktop app (Electron, Qt, GTK, Rust/Tauri). Ships on Windows and macOS. Dreads Linux.

**Current pain:**
- Must choose between Flatpak, Snap, AppImage, .deb, .rpm — or all of them
- Flatpak requires learning manifest format, runtimes, SDK extensions. If a dependency isn't in the runtime, must package it manually. ([BrixIT](https://blog.brixit.nl/developers-are-lazy-thus-flatpak/))
- Snap review queue is opaque and slow for classic confinement
- AppImage "just works" for building but has no discovery channel — users can't find the app
- Distro packagers introduce bugs the developer must triage ([DuckStation incident](https://news.lavx.hu/article/duckstation-developer-blocks-arch-linux-packaging-citing-license-violations-and-maintenance-burden))
- ["Packaging has a well-earned reputation for being cumbersome, thankless and finicky"](https://blogs.vmware.com/opensource/2022/08/30/of-builds-and-packaging/)

**What they want:** Publish once. Users find it. Done. No manifest, no review queue, no maintaining 5 build pipelines.

**Zapstore value:** `zsp publish` — one command, sign with npub, upload binary, publish NIP-82 events. No manifest files, no runtime dependencies, no review queue, no gatekeeper.

**Zapstore friction:** Needs a Nostr keypair. Trivial to create, but unfamiliar if they don't already use Nostr.

### 2. The Nostr Developer — "I already have an npub"

**Profile:** Builds Nostr clients, tools, or related apps. Already has a keypair, understands relays, publishes notes.

**Current pain:**
- Ships AppImage or .deb on GitHub Releases
- Users must find the repo, download manually, `chmod +x`, figure out desktop integration
- No app store presence — the app is invisible to non-technical users
- No update notification mechanism

**What they want:** Their app in a store that their community already uses. Direct zap support. Discoverable via Nostr identity.

**Zapstore value:** Perfect fit. Already has npub, already understands the protocol. Zapstore is the native distribution channel. Zaps for direct support. App discoverable through web of trust.

**Zapstore friction:** Almost none. This is the seed catalog.

### 3. The Linux Power User — "I run Arch, btw"

**Profile:** Runs Arch, Fedora, or NixOS. Comfortable with terminal. Has opinions about Flatpak vs native packages. Reads r/linux.

**Current pain:**
- Annoyed by Flatpak bloat (75x storage vs RPM) and theming issues
- Distrusts Snap (proprietary backend, forced updates, corporate control)
- Uses AppImage sometimes but frustrated by no auto-updates, no desktop integration, no central catalog
- Reads about Snap Store malware and Flathub verification gaps — worried about supply chain
- Exhausted by format wars

**What they want:** Verifiable provenance. No corporate gatekeeper. Minimal bloat. Control over updates. Ideally one source that works.

**Zapstore value:** Cryptographic verification (developer npub signs every release). No corporate backend. Open protocol. Aligns with sovereignty values. Decentralized architecture resonates with this audience.

**Zapstore friction:** "Yet another app store" fatigue. Will be skeptical until they see real apps in the catalog. Needs to see Gossip, Amethyst-like apps they already use before they trust the platform.

### 4. The Linux Newcomer — "I just switched from Windows"

**Profile:** Installed Ubuntu or Mint because Windows did something they didn't like. Used to Windows Store or just downloading .exe files. Not comfortable with terminal.

**Current pain:**
- Confused by multiple ways to install apps (apt, Snap, Flatpak, .deb, AppImage, Software Center)
- Software center is slow (GNOME Software) or crashes (old Pop!_Shop)
- Finds an app online, downloads .AppImage, doesn't know what to do with it — no double-click install, no menu entry
- Doesn't understand "verified" vs "unverified" warnings on Flathub
- ["The bash shell still differs dramatically from Windows command prompts, and error messages remain cryptic for newcomers"](https://medium.com/@saehwanpark/a-second-look-at-linux-reflections-from-2025-f9285809925e)

**What they want:** Click install. App appears in menu. Click to launch. Automatic updates. That's it.

**Zapstore value:** If the UX is polished — browse, click install, app appears in menu, click to launch — this is what they want. No terminal needed.

**Zapstore friction:** Must install zapstore itself first (chicken-and-egg). Won't understand Nostr, npubs, or web of trust. Needs the store to "just work" without understanding the protocol. Empty catalog is a dealbreaker — they'll try it once, see no apps they recognize, and uninstall.

### 5. The Enterprise IT Manager — "I need to control what gets installed"

**Profile:** Manages a fleet of Linux workstations (dev shop, university lab, government office). Responsible for security compliance.

**Current pain:**
- No Linux app store has whitelist/blacklist capability ([COSMIC Store #427](https://github.com/pop-os/cosmic-store/issues/427))
- Snap Store has had malware incidents — unacceptable for enterprise
- Flatpak's "unverified" apps with broad permissions are a compliance risk
- Must manually audit what employees install
- No MDM-style app management for Linux desktops

**What they want:** Curated, approved app catalog. Whitelist enforcement. Audit trail. No unapproved software.

**Zapstore value:** NIP-51 curation sets could map to enterprise allow-lists. Cryptographic provenance provides audit trail (every install traceable to a signed event from a known publisher). Could define "only apps signed by these npubs" policy.

**Zapstore friction:** This persona needs features that don't exist yet (admin policy enforcement, fleet management). Not a Phase 1 audience, but the architecture supports it. No compliance certifications, no enterprise support contract.

### 6. The Distro Maintainer — "I package software for a living"

**Profile:** Volunteers or is employed to maintain packages for Debian, Fedora, Arch, etc. Battles dependency hell daily.

**Current pain:**
- Upstream developers don't care about distro packaging — "just download the binary from our website"
- Must patch software to work with distro-specific library versions
- Flatpak/AppImage/Snap are seen as threats to the traditional packaging model
- Upstream developers sometimes hostile — DuckStation, others have blocked distro packaging
- Burnout: ["Linux Mint's success also means maintainer stress"](https://www.theregister.com/2026/02/16/mints_success_and_stress/)

**What they want:** Upstream to provide stable, well-documented source releases. Or: for universal formats to actually work so they don't have to package everything themselves.

**Zapstore value:** Minimal. Distro maintainers are not zapstore's audience — zapstore bypasses the distro packaging model entirely (developer publishes directly). This is a feature for developers and users, but distro maintainers may see it as competition.

**Zapstore friction:** Philosophical opposition. Distro maintainers believe in the curation model where they vet and patch software. Zapstore's "developer publishes directly" model removes them from the chain. They may view this negatively.

### 7. The FOSS Advocate — "Software freedom matters"

**Profile:** Cares deeply about free software principles. Uses Debian, Trisquel, or Guix. Reads FSF newsletters. Suspicious of anything proprietary or centralized.

**Current pain:**
- Snap Store's proprietary backend is unacceptable
- Flathub mixes free and proprietary software without clear separation ([flatpak #5654](https://github.com/flatpak/flatpak/issues/5654))
- GitHub is owned by Microsoft — even hosting releases there feels wrong
- No app store enforces or even tracks software licenses properly

**What they want:** Fully open infrastructure. Clear license information. No proprietary dependencies anywhere in the chain. Community governance.

**Zapstore value:** Open protocol (Nostr). No proprietary backend. Decentralized relays. No corporate gatekeeper. Aligns with software freedom values — the protocol is the standard, not a company's server.

**Zapstore friction:** Zapstore itself is not (currently) packaged for purist distros. Bitcoin/Lightning association may carry ideological baggage for some. NIP-82 events don't currently carry license metadata (could be added).

---

## Managing the "Yet Another Solution" Fatigue

The community is tired of new packaging formats and app stores. Every multi-format store project has failed. How zapstore avoids this trap:

**1. Don't compete on format — compete on distribution model.**
Zapstore doesn't invent a new packaging format. It uses AppImage (endorsed by Torvalds, already understood). The innovation is the distribution layer (Nostr), not the package format. Message: "Same AppImages you already use. Different way to find, verify, and install them."

**2. Ship with apps, not promises.**
Every dead Linux app store (AppOutlet, linuxappstore, bauh) launched empty and died empty. Zapstore must have a populated catalog at launch. The plan's "supply before demand" phase order addresses this — populate the relay before shipping the client.

**3. Piggyback on an existing community.**
Zapstore doesn't need to convince the entire Linux community. It needs to serve the Nostr community first — developers who already have npubs, users who already understand web of trust. This is a real, active community with real apps (Gossip, Notedeck, Amethyst). Expand from there.

**4. Demonstrate verification, not just claim it.**
Every other store says "trust us." Zapstore can show the cryptographic chain: "This binary was published by npub1abc..., who is [developer name], and the SHA-256 hash matches." That's not a badge — it's math. Show this in the UI.

**5. Don't ask users to understand Nostr.**
Users don't need npubs. They don't need to know what a relay is. They need: browse, install, launch. The protocol is invisible infrastructure, like TCP/IP is to web browsing.

---

## Key Sources

**Linus Torvalds:**
- [DebConf 2014 Q&A (video)](https://www.youtube.com/watch?v=5PmHRSeA2c8) — the packaging rant
- [AppImage endorsement (archived from Google+)](https://appimage.org/) — "This is just very cool"
- [Shared libraries LKML thread](https://lwn.net/Articles/855464/) — "not a good thing in general"
- [LinuxCon Europe 2013 keynote](https://www.linuxfoundation.org/blog/blog/10-best-quotes-from-linus-torvalds-keynote-at-linuxcon-europe) — Valve and market-driven standards
- [Don't break userspace (LKML)](https://lkml.org/lkml/2012/12/23/75) — "WE DO NOT BREAK USERSPACE"
- [Fragmentation held desktop back (TFiR/It's FOSS)](https://itsfoss.com/desktop-linux-torvalds/) — 2018 interview

**AppImage:**
- [AppImages: the worst choice in "portability"](https://ludditus.com/2024/10/31/appimage/) — ludditus.com
- [dont-use-appimages](https://github.com/boredsquirrel/dont-use-appimages) — GitHub
- [Linux Deserves Better: The Future of AppImage Integration](https://pedroinnecco.com/2025/09/linux-deserves-better-the-future-of-appimage-integration/) — Pedro Innecco
- [AppImages are just .exe files for Linux](https://www.xda-developers.com/appimages-are-just-exe-files-for-linux-and-nobody-explains-it-that-simply/) — XDA
- [Usability problems with Appimages](https://discourse.appimage.org/t/usability-problems-with-appimages/340) — AppImage Discourse
- [AppImageKit #133](https://github.com/AppImage/AppImageKit/issues/133) — self-updatable AppImages
- [AppImageKit #238](https://github.com/AppImage/AppImageKit/issues/238) — signing
- [AppImageKit #175](https://github.com/AppImage/AppImageKit/issues/175) — P2P distribution (82 comments)
- [AM #2104](https://github.com/ivan-hc/AM/issues/2104) — no binary integrity verification

**Flatpak/Flathub:**
- [Flatpak Is Not the Future](https://ludocode.com/blog/flatpak-is-not-the-future) — ludocode
- [Flatpak "not being actively developed anymore"](https://www.osnews.com/story/142467/flatpak-not-being-actively-developed-anymore/) — OSnews
- [Flatpak development restarts](https://linuxiac.com/flatpak-development-restarts-with-fresh-energy-and-clear-direction/) — Linuxiac
- [Building a more respectful App Store](https://tim.siosm.fr/blog/2025/11/24/building-better-app-store-flathub/) — Siosm
- [Flathub safety: a layered approach](https://docs.flathub.org/blog/app-safety-layered-approach-source-to-user) — Flathub docs
- [flatkill.org](https://flatkill.org/) — sandbox security criticism
- [flatpak #5654](https://github.com/flatpak/flatpak/issues/5654) — block unverified flatpaks by default
- [flathub #4855](https://github.com/flathub/flathub/issues/4855) — verification is app-wide not per-build

**Snap:**
- [Exodus Bitcoin Wallet $490K Swindle](https://popey.com/blog/2024/02/exodus-bitcoin-wallet-490k-swindle/) — Alan Pope
- [Snapcraft Forum: fake Exodus wallet report](https://forum.snapcraft.io/t/report-of-fake-crypto-wallet-exodus-snap-s/49161)
- [Malware via hijacked Snap publisher domains](https://blog.popey.com/2026/01/malware-purveyors-taking-over-published-snap-email-domains/) — Alan Pope
- [There is still no Linux app store](https://blog.popey.com/2023/09/there-is-still-no-linux-app-store/) — Alan Pope
- [Snap auto updates broke my setup](http://raymii.org/s/blog/Ubuntu_Snap_auto_updates_broke_my_development_setup.html) — Raymii
- [Linux Mint drops Snap](https://lwn.net/Articles/825005/) — LWN
- [Please address "store is not open-source"](https://forum.snapcraft.io/t/please-address-store-is-not-open-source-again/18442) — Snapcraft Forum
- [Canonical restricts Snap registrations](https://www.theregister.com/2024/03/28/canonical_snap_store_scams/) — The Register
- [Snap Store malware (Bitdefender)](https://www.bitdefender.com/en-us/blog/hotforsecurity/canonical-changes-snap-store-policy-in-ubuntu-after-criminals-upload-fake-crypto-apps)
- [Snap Store malware (Phoronix)](https://www.phoronix.com/news/Snap-Store-Malicious-Apps)

**General / App Store UX:**
- [Linux's app problem isn't compatibility anymore](https://www.xda-developers.com/linuxs-app-problem-app-stores-refuse-merge/) — XDA
- [Distribution packaging is unsustainable](https://memoryfile.codeberg.page/posts/Distribution-packaging-for-Linux-desktop-applications-is-unsustainable/) — memoryfile
- [Federated app store proposal](https://lemmy.ml/post/27943303) — Lemmy
- [COSMIC Store issues](https://github.com/pop-os/cosmic-store/issues) — GitHub
- [Snap vs Flatpak Guide 2025](https://www.glukhov.org/post/2025/12/snap-vs-flatpack/) — glukhov.org
- [elementary AppCenter + Flatpak](https://blog.elementary.io/elementary-appcenter-flatpak/) — elementary Blog
- [Flathub LLC proposal](https://github.com/PlaintextGroup/oss-virtual-incubator/blob/main/proposals/flathub-linux-app-store.md) — PlaintextGroup

**AppImage:**
- [AppImages: the worst choice in "portability"](https://ludditus.com/2024/10/31/appimage/) — ludditus.com
- [dont-use-appimages](https://github.com/boredsquirrel/dont-use-appimages) — GitHub
- [Linux Deserves Better: The Future of AppImage Integration](https://pedroinnecco.com/2025/09/linux-deserves-better-the-future-of-appimage-integration/) — Pedro Innecco
- [AppImages are just .exe files for Linux](https://www.xda-developers.com/appimages-are-just-exe-files-for-linux-and-nobody-explains-it-that-simply/) — XDA
- [Usability problems with Appimages](https://discourse.appimage.org/t/usability-problems-with-appimages/340) — AppImage Discourse
- [AppImageKit #133](https://github.com/AppImage/AppImageKit/issues/133) — self-updatable AppImages
- [AppImageKit #238](https://github.com/AppImage/AppImageKit/issues/238) — signing
- [AppImageKit #175](https://github.com/AppImage/AppImageKit/issues/175) — P2P distribution (82 comments)
- [AM #2104](https://github.com/ivan-hc/AM/issues/2104) — no binary integrity verification

**Flatpak/Flathub:**
- [Flatpak Is Not the Future](https://ludocode.com/blog/flatpak-is-not-the-future) — ludocode
- [Flatpak "not being actively developed anymore"](https://www.osnews.com/story/142467/flatpak-not-being-actively-developed-anymore/) — OSnews
- [Flatpak development restarts](https://linuxiac.com/flatpak-development-restarts-with-fresh-energy-and-clear-direction/) — Linuxiac
- [Building a more respectful App Store](https://tim.siosm.fr/blog/2025/11/24/building-better-app-store-flathub/) — Siosm
- [Flathub safety: a layered approach](https://docs.flathub.org/blog/app-safety-layered-approach-source-to-user) — Flathub docs
- [flatkill.org](https://flatkill.org/) — sandbox security criticism
- [flatpak #5654](https://github.com/flatpak/flatpak/issues/5654) — block unverified flatpaks by default
- [flathub #4855](https://github.com/flathub/flathub/issues/4855) — verification is app-wide not per-build

**Snap:**
- [Malware via hijacked Snap publisher domains](https://blog.popey.com/2026/01/malware-purveyors-taking-over-published-snap-email-domains/) — Alan Pope
- [There is still no Linux app store](https://blog.popey.com/2023/09/there-is-still-no-linux-app-store/) — Alan Pope
- [Snap auto updates broke my setup](http://raymii.org/s/blog/Ubuntu_Snap_auto_updates_broke_my_development_setup.html) — Raymii
- [Linux Mint drops Snap](https://lwn.net/Articles/825005/) — LWN
- [Please address "store is not open-source"](https://forum.snapcraft.io/t/please-address-store-is-not-open-source-again/18442) — Snapcraft Forum

**General / App Store UX:**
- [Linux's app problem isn't compatibility anymore](https://www.xda-developers.com/linuxs-app-problem-app-stores-refuse-merge/) — XDA
- [Distribution packaging is unsustainable](https://memoryfile.codeberg.page/posts/Distribution-packaging-for-Linux-desktop-applications-is-unsustainable/) — memoryfile
- [Federated app store proposal](https://lemmy.ml/post/27943303) — Lemmy
- [COSMIC Store issues](https://github.com/pop-os/cosmic-store/issues) — GitHub
- [Snap vs Flatpak Guide 2025](https://www.glukhov.org/post/2025/12/snap-vs-flatpack/) — glukhov.org
- [elementary AppCenter + Flatpak](https://blog.elementary.io/elementary-appcenter-flatpak/) — elementary Blog
