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
	return ServiceProfile{ServiceID: "dispatch", Domain: "Dispatch", Language: "golang", Phase: "phase-1-core", WorkPackages: []string{"REQ-114"}, Owns: []string{"RideRequest", "RideAssignment", "DriverLifecycle", "Eta"}}
}
func Health() string { return "ok" }

const WorkPackageSummary = "REQ-114"
const OwnershipSummary = "RideRequest, RideAssignment, DriverLifecycle, Eta"
