export type ServiceProfile = {
  serviceId: string;
  domain: string;
  language: "typescript";
  phase: string;
  workPackages: string[];
  owns: string[];
};

export const serviceProfile: ServiceProfile = {
  serviceId: "notification",
  domain: "Notification",
  language: "typescript",
  phase: "phase-1-support",
  workPackages: ["WP-15"],
  owns: ["NotificationTask", "Template", "RecipientPolicy", "DeliveryReceipt"],
};
