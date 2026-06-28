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
		ServiceID:    "provider-integration",
		Domain:       "Provider Integration",
		Language:     "golang",
		Phase:        "phase-1-support",
		WorkPackages: []string{"WP-11"},
		Owns:         []string{"adapter protocol", "signature verification", "raw archive", "external status mapping"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "WP-11"
const OwnershipSummary = "adapter protocol, signature verification, raw archive, external status mapping"
