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
		ServiceID:    "place-network",
		Domain:       "Place & Network",
		Language:     "golang",
		Phase:        "phase-1-core",
		WorkPackages: []string{"WP-02"},
		Owns:         []string{"Place", "TransportNode", "ProviderPlaceMapping"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "WP-02"
const OwnershipSummary = "Place, TransportNode, ProviderPlaceMapping"
