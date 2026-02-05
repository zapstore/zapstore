# FEAT-003 — Updates Screen

## Goal

Show users their installed apps with available updates, allow manual refresh, and automatically poll for new updates in the background while the app is in foreground.

## Non-Goals

- Background polling when app is not in foreground (handled by WorkManager in FEAT-002)
- Auto-installation without user action (Update All requires explicit tap)
- Per-app update scheduling or deferral

## User-Visible Behavior

### Updates Screen

- Shows categorized lists: Installing, Updates (automatic), Manual Updates, Up to Date, Other Installed
- "Update All" button at top updates all apps with automatic update capability
- "Last checked" timestamp shows when updates were last fetched
- Pull-to-refresh triggers immediate update check (with throttling)

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

## Acceptance Criteria

- [ ] Update polling starts on app launch (not just when visiting Updates screen)
- [ ] Polling continues when user is on Search or Profile screens
- [ ] Pull-to-refresh throttled: ignored if checking or checked <30s ago
- [ ] Badge count updates reactively when new updates discovered
- [ ] "Last checked" timestamp displays relative time (e.g., "2 minutes ago")
- [ ] Skeleton shown only on cold start until first installed app matches
- [ ] Subsequent refreshes show spinner, not skeleton
