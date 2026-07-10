package domain

const ServiceID = "travel-insurance"

type ServiceProfile struct {
	ServiceID    string   `json:"serviceId"`
	Domain       string   `json:"domain"`
	Language     string   `json:"language"`
	Phase        string   `json:"phase"`
	WorkPackages []string `json:"workPackages"`
	Owns         []string `json:"owns"`
	Endpoints    []string `json:"endpoints"`
}

func Profile() ServiceProfile {
	return ServiceProfile{
		ServiceID:    ServiceID,
		Domain:       "Travel Insurance",
		Language:     "golang",
		Phase:        "phase-1-core",
		WorkPackages: []string{"REQ-213"},
		Owns:         []string{"InsuranceProduct", "Policy", "Claim", "PayoutAdvice", "UnderwritingRequestLog"},
		Endpoints:    []string{"POST /api/v1/policies", "GET /api/v1/policies/:id", "POST /api/v1/claims"},
	}
}

func Health() string { return "ok" }
