# Business context map

Last updated: 2026-09-24.

| Owner | Owns | Collaboration |
|---|---|---|
| Place & Network | Places, nodes and reference resolution. | Service Plan and Trip Planning use stable node references. |
| Service Plan | Scheduled service and segment times. | Trip Planning consumes source events; internal supply reads expose ordered stops and intervals. |
| Trip Planning | Candidate search, ranking and itinerary snapshots. | Offer and loadgen consume actual itinerary references. |
| Fare & Pricing | Fare quotes, rule calculations and eligibility inputs. | Offer freezes quote references; Post Sales evaluates adjustment decisions. |
| Offer Management | Accepted quotation, disclosures and expiry. | Journey Order records accepted Offer references. |
| Journey Order | Commercial order, traveler and segment summaries. | Consumes booking, funds and ticket facts; does not execute channels or allocate seats. |
| Booking Orchestration | Booking saga and traveler reservations. | Coordinates Capacity, Payment, Provider Integration and ticketing facts. |
| Capacity & Availability | Holds and inventory admission. | Seat Assignment owns physical selection; combined supply integration remains unpublished. |
| Seat Assignment | Published composition and physical or standing allocation. | Entitlement uses the persisted allocation; standing policy has no physical SeatUnit. |
| Payment | Captures, refunds, callback identity and funding balances. | Payment Channel owns simulated channel operations and statements. |
| Entitlement & Ticketing | Ticket identity and usable travel rights. | Fulfillment records boarding and completion; Post Sales controls authorized void/change operations. |
| Post Sales | Refund/change decisions and recorded execution. | Wallet compensation preserves travel; approved refunds execute through Payment. |
| Wallet / Promotion | Benefits, balances, redemptions and compensation issuance deduplication. | Post Sales supplies approved value; Finance records benefit cost. |
| Finance Settlement | Revenue recognition, fees, reconciliation and benefit costs. | Invoicing consumes financial bases; Reporting consumes business events. |
| Invoicing | Invoice title, invoice request, issued invoice and red flush. | Uses its own order and amount projections. |
| Reporting | Metrics and dashboard projections. | Monetary revenue fields use gross PaymentCaptured, without changing transaction owners. |
| Notification | Templates, recipient preferences and delivery receipts. | Delivery never establishes ticket, payment or order success. |
| Identity Verification and Traveler Profile | Purchase eligibility evidence and traveler data respectively. | Order creation and fare usage retain their actual source references. |
| Transfer Management and Disruption Recovery | Connections, protection choices and recovery execution. | Recovery routes to the appropriate Post Sales, wallet or manual operation. |
| Waitlist | Candidate request, selection window and promotion workflow. | Booking and payment remain independent owners. |

Supplier Catalog, Provider Integration, Account, Risk, Customer Service and Admin Audit retain their own reference, policy and controlled operation boundaries.
Ancillary, insurance, group, corporate, loyalty, marketing and dispatch services retain separate lifecycles and must not directly rewrite primary ticket or payment state.

[Domain details](../02-domains/README.md) and [APIs](../08-contracts/api/README.md) define concrete operations.
Remaining integration and verification work is in [TODO](../TODO.md).
