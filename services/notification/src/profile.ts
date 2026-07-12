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
  serviceId: "notification",
  domain: "Notification",
  language: "typescript",
  phase: "phase-1-domain-foundation",
  requirement: "REQ-019-Notification-domain-foundation",
  workPackages: ["REQ-019", "WP-15-transaction-notification"],
  owns: [
    "NotificationTask lifecycle",
    "Template versioning and channel adaptations",
    "RecipientPolicy with channel priority and fallback",
    "DeliveryReceipt appended facts",
    "NotificationScheduled",
    "NotificationDispatched",
    "NotificationDelivered",
    "NotificationFailed",
    "NotificationCancelled",
  ],
  doesNotOwn: [
    "JourneyOrder",
    "CapacityHold",
    "PaymentIntent",
    "Entitlement",
    "Business state rollback",
  ],
  publishes: [
    "NotificationScheduled",
    "NotificationDispatched",
    "NotificationDelivered",
    "NotificationFailed",
    "NotificationCancelled",
  ],
};
