export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  workPackages: string[];
  owns: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "customer-service",
  domain: "Customer Service",
  language: "typescript",
  phase: "phase-1-support",
  workPackages: ["WP-20"],
  owns: ["SupportCase", "EvidenceRef", "ManualActionRequest", "CaseTimeline"],
};
