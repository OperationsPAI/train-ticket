package domain

type ServiceProfile struct {
	ServiceID   string   `json:"serviceId"`
	Name        string   `json:"name"`
	Domain      string   `json:"domain"`
	Version     string   `json:"version"`
	Description string   `json:"description"`
	Endpoints   []string `json:"endpoints"`
	Publishes   []string `json:"publishes"`
	Consumes    []string `json:"consumes"`
}

func Profile() ServiceProfile {
	return ServiceProfile{ServiceID: "travel-insurance", Name: "Travel Insurance", Domain: "Travel Insurance", Version: "0.1.0", Description: "Insurance product catalog, policy issuance, offers, and claims", Endpoints: []string{"POST /policies", "GET /policies/:id", "POST /claims", "POST /api/v1/policies", "GET /api/v1/policies/:id", "POST /api/v1/claims"}, Publishes: []string{EventPolicyIssued, EventClaimFiled, EventClaimSettled}, Consumes: []string{"JourneyOrderConfirmed from events:journey-order", "PostSalesApproved/RefundApproved from events:post-sales"}}
}

func Health() string { return "ok" }
