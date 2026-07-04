export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  requirement: string;
  workPackages: string[];
  owns: string[];
  doesNotOwn: string[];
  publishes: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "account",
  domain: "Account",
  language: "typescript",
  phase: "phase-1-account-domain-foundation",
  requirement: "REQ-021-Account-domain-foundation",
  workPackages: ["REQ-021", "WP-18-account-minimum"],
  owns: [
    "UserAccount lifecycle (create, freeze, unfreeze, closure)",
    "Session lifecycle (open, revoke, expiry)",
    "Preference management (update)",
    "Freeze invariant (blocks new transactions, not refunds/notifications)",
    "AccountClosureSaga shell (verify in-flight orders/refunds before final closure)",
    "AccountCreated, AccountFrozen, AccountUnfrozen, AccountClosureStarted, AccountClosed",
    "SessionOpened, SessionRevoked, PreferenceUpdated",
  ],
  doesNotOwn: [
    "Traveler Profile facts (documents, eligibility, preferences)",
    "Payment funds, balances, or refund execution",
    "Risk assessment or compliance decisions",
    "Journey Order state or lifecycle",
  ],
  publishes: [
    "AccountCreated",
    "AccountFrozen",
    "AccountUnfrozen",
    "AccountClosureStarted",
    "AccountClosed",
    "SessionOpened",
    "SessionRevoked",
    "PreferenceUpdated",
  ],
};
