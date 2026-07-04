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
  serviceId: "customer-service",
  domain: "Customer Service",
  language: "typescript",
  phase: "phase-1-support",
  requirement: "REQ-023-Customer-Service-domain-foundation",
  workPackages: ["REQ-023", "WP-20-customer-service-cases"],
  owns: [
    "SupportCase lifecycle and state machine",
    "EvidenceRef attachment and access control",
    "ManualActionRequest lifecycle and outcome recording",
    "CaseTimeline append-only entries",
    "SupportCaseOpened",
    "EvidenceAttached",
    "SupportCaseClassified",
    "SupportCaseAssigned",
    "ManualActionRequested",
    "ManualActionResultRecorded",
    "SupportCaseEscalated",
    "SupportCaseResolved",
    "SupportCaseClosed",
    "SupportCaseReopened",
    "CaseTimelineEntryAppended",
  ],
  doesNotOwn: [
    "JourneyOrder",
    "CapacityHold",
    "PaymentIntent",
    "Entitlement",
    "PostSalesCase",
    "Business state mutation",
  ],
  publishes: [
    "SupportCaseOpened",
    "EvidenceAttached",
    "SupportCaseClassified",
    "SupportCaseAssigned",
    "ManualActionRequested",
    "ManualActionResultRecorded",
    "SupportCaseEscalated",
    "SupportCaseResolved",
    "SupportCaseClosed",
    "SupportCaseReopened",
    "CaseTimelineEntryAppended",
  ],
};
