export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  workPackages: string[];
  owns: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "ancillary-service",
  domain: "Ancillary Service",
  language: "typescript",
  phase: "future-scope",
  workPackages: [],
  owns: ["AncillaryOffer", "AncillaryOrderItem", "ActivationPolicy"],
};
