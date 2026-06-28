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
		WorkPackages: []string{"WP-13"},
		Owns:         []string{"FulfillmentRecord", "BoardingVerified", "NoShow", "EvidenceDispute"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "WP-13"
const OwnershipSummary = "FulfillmentRecord, BoardingVerified, NoShow, EvidenceDispute"
