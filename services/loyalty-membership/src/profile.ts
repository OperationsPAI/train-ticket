export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  requirement: string;
  owns: string[];
  consumes: string[];
  publishes: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "loyalty-membership",
  domain: "Loyalty Membership",
  language: "typescript",
  phase: "domain-enrichment-loyalty-membership",
  requirement: "REQ-309-loyalty-membership-points-and-tiers",
  owns: [
    "Membership tier state",
    "Redeemable points balance",
    "Points ledger entries and lots",
    "Tier upgrade and downgrade rules",
  ],
  consumes: ["JourneyOrderCreated", "JourneyOrderCancelled", "PaymentCaptured", "PostSalesApplied"],
  publishes: ["PointsEarned", "PointsRedeemed", "PointsRestored", "PointsExpired", "MemberTierUpgraded", "MemberTierDowngraded", "TierEvaluationCompleted"],
};
