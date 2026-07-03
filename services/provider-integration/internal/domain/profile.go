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
		Phase:        "phase-1-acl-foundation",
		WorkPackages: []string{"REQ-015"},
		Owns: []string{
			"provider request and callback identity",
			"idempotency scope",
			"raw archive references",
			"external status and error mapping",
			"unmapped status quarantine",
			"retry and manual-review directives",
		},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "REQ-015 Provider Integration ACL foundation"
const OwnershipSummary = "provider request/callback identity, idempotency scope, raw archive references, status/error mapping, unmapped status quarantine, retry/manual review directives"
