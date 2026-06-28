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
		ServiceID:    "supplier-catalog",
		Domain:       "Supplier Catalog",
		Language:     "golang",
		Phase:        "phase-1-support",
		WorkPackages: []string{"WP-02", "WP-11"},
		Owns:         []string{"Supplier", "Carrier", "Contract", "ProductCapability", "ExternalCode"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "WP-02, WP-11"
const OwnershipSummary = "Supplier, Carrier, Contract, ProductCapability, ExternalCode"
