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
  phase: "phase-1-loyalty-membership-service-foundation",
  requirement: "REQ-210-Loyalty-membership-service-foundation",
  owns: [
    "Membership tier state",
    "Redeemable points balance",
    "Points ledger entries and lots",
    "Tier upgrade and downgrade rules",
  ],
  consumes: ["JourneyOrderConfirmed", "PaymentCaptured"],
  publishes: ["MembershipTierChanged", "PointsAccrued", "PointsRedeemed"],
};
