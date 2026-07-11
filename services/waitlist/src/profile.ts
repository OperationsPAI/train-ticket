export const serviceProfile = {
  serviceId: "waitlist",
  domain: "Waitlist",
  language: "typescript",
  phase: "phase-1-activation",
  workPackages: ["REQ-305"],
  owns: ["WaitlistEntry", "WaitlistQueue", "WaitlistOffer"],
} as const;
