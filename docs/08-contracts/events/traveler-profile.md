# Traveler Profile — Events & Commands

Last updated: 2026-06-28

## Published Events

### TravelerSnapshotUpdated

| Field | Description |
|---|---|
| **Producer** | traveler-profile |
| **Consumers** | offer-management, journey-order |
| **Trigger** | Traveler profile data changed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | `TravelerId` | yes | Canonical traveler ID (`tvl-<uuid>`). |
| `snapshotVersion` | string | yes | Monotonically increasing version. |
| `updatedAt` | RFC3339 UTC | yes | Update timestamp. |
