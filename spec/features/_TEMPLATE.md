# FEAT-XXX — Short Name

## Goal

1–2 sentences describing what this feature/bugfix achieves for the user.

## Non-Goals

- Explicitly list what is out of scope
- Prevents scope creep and AI drift

## User-Visible Behavior

- What the user sees or can do
- States: loading, success, error, empty (where relevant)
- Offline behavior

## Edge Cases

- Degraded or no network
- Cancellation / retry
- Invalid or partial data
- Permission denied
- Other relevant risks

## Acceptance Criteria

- [ ] Observable outcome 1
- [ ] Observable outcome 2
- [ ] Observable outcome 3

## Notes (optional)

- Anything that needs human decision
- Open questions

---

# Example: FEAT-002 — NWC Zaps

## Goal

Allow users to zap app developers directly from the app detail screen using Nostr Wallet Connect.

## Non-Goals

- In-app wallet management (just NWC connection)
- Zapping comments or reviews (only developers)
- Recurring zaps or subscriptions

## User-Visible Behavior

- Zap button visible on app detail screen when NWC connected
- Tapping opens amount selection dialog (21, 100, 500, 1000 sats, custom)
- Success: toast confirmation with amount
- Failure: error dialog with reason
- Button disabled with tooltip when developer has no lightning address

## Edge Cases

- Developer has no lightning address → button hidden or disabled with explanation
- NWC connection drops mid-zap → graceful error, suggest reconnect
- Insufficient wallet balance → clear error from wallet
- App backgrounded during zap → completes, toast on return

## Acceptance Criteria

- [ ] User can connect NWC from profile settings
- [ ] User can zap developer from app detail screen
- [ ] Zap fails gracefully with clear error message
- [ ] Zap button correctly disabled when NWC not connected

## Notes

- Consider whether to show cumulative zaps received by developer
