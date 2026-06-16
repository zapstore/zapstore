# FEAT-003 — Updates Screen

## Goal

Show users their installed apps with available updates, allow manual refresh, and automatically poll for new updates in the background while the app is in foreground.

## Non-Goals

- Background polling when app is not in foreground (handled by WorkManager in FEAT-002)
- Auto-installation without user action (Update All requires explicit tap)
- Per-app update scheduling or deferral

## User-Visible Behavior

### Updates Screen

- Shows categorized lists: Installing, Updates (automatic), Manual Updates, Up to Date, Other Installed, Unmanaged Apps
- "Update All" button at top updates all apps with automatic update capability
- "Last checked" timestamp shows when updates were last fetched
- Pull-to-refresh triggers immediate update check (with throttling)
- Apps marked unmanaged are excluded from update counts and "Update All"
- Cataloged unmanaged apps keep Zapstore metadata such as icon, publisher, description, and version data; uncataloged unmanaged apps fall back to installed package metadata
- Apps installed by another app store may default to unmanaged; apps installed manually, through browser/file-manager/package-installer flows, or by Zapstore stay managed by default
- Users can override either default with explicit Unmanage/Manage actions, and explicit choices persist across restarts

### Cold Start Behavior

- Skeleton UI shown only until ANY installed app has matching data from relays
- Once first match received, skeleton replaced with actual categorized list
- Apps without relay data appear in "Other Installed" section
- Subsequent polling never shows skeleton, only spinner next to "Last checked"

### Global Polling

- Update polling runs globally while app is in foreground, regardless of which screen user is viewing
- Polls every 5 minutes for new releases from remote relays
- Badge on Updates tab shows count of available updates
- Loading indicator on badge while: skeleton is showing OR actively checking for updates

### Pull-to-Refresh Throttling

- If already checking for updates, pull-to-refresh is ignored
- If last check was less than 30 seconds ago, pull-to-refresh is ignored
- User sees refresh indicator briefly animate and complete (no visual indication of throttle)

### Reactivity

- Updates discovered from any source (polling, Latest Releases stream, etc.) appear immediately
- No manual refresh required to see updates from other data sources

## Edge Cases

- No installed apps → empty state with "Install some apps" message
- Network offline during poll → fails silently, retries on next interval
- App backgrounded → polling pauses, resumes when app returns to foreground
- Rapid pull-to-refresh → throttled to prevent server spam
- Installer source unavailable or ambiguous → app remains managed by default
- Explicit Manage must not be undone by automatic installer-source detection on the next package scan

## Acceptance Criteria

- [ ] Update polling starts on app launch (not just when visiting Updates screen)
- [ ] Polling continues when user is on Search or Profile screens
- [ ] Pull-to-refresh throttled: ignored if checking or checked <30s ago
- [ ] Badge count updates reactively when new updates discovered
- [ ] "Last checked" timestamp displays relative time (e.g., "2 minutes ago")
- [ ] Skeleton shown only on cold start until first installed app matches
- [ ] Subsequent refreshes show spinner, not skeleton
- [ ] Unmanaged apps excluded from update count and "Update All"
- [ ] Cataloged unmanaged apps retain Zapstore metadata in the Unmanaged Apps section
- [ ] Other-store installs default unmanaged when installer source is known
- [ ] Browser/manual/package-installer installs default managed
- [ ] Explicit Manage/Unmanage choices override installer-source defaults and persist
