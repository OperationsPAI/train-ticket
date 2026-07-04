export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  workPackages: string[];
  owns: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "account",
  domain: "Account",
  language: "typescript",
  phase: "phase-1-limited",
  workPackages: ["WP-18"],
  owns: ["UserAccount", "Session", "Preference", "Freeze", "AccountClosureSaga shell"],
};
