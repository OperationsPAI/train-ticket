package domain

type ServiceProfile struct {
	ServiceID    string   `json:"serviceId"`
	Domain       string   `json:"domain"`
	Language     string   `json:"language"`
	Phase        string   `json:"phase"`
	WorkPackages []string `json:"workPackages"`
	Owns         []string `json:"owns"`
}

func Profile() ServiceProfile {
	return ServiceProfile{
		ServiceID:    "fulfillment",
		Domain:       "Fulfillment",
		Language:     "golang",
		Phase:        "phase-1-limited",
		WorkPackages: []string{"REQ-018"},
		Owns: []string{
			"FulfillmentRecord aggregate",
			"EvidenceDispute aggregate",
			"BoardingVerified event",
			"NoShowRecorded event",
			"FulfillmentCompleted event",
			"EvidenceDisputeOpened event",
			"EvidenceDisputeResolved event",
		},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "REQ-018 Fulfillment facts domain foundation"
const OwnershipSummary = "FulfillmentRecord, EvidenceDispute, BoardingVerified, NoShowRecorded, FulfillmentCompleted, EvidenceDisputeOpened, EvidenceDisputeResolved"
