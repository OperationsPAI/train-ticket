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
		ServiceID:    "service-plan",
		Domain:       "Service Plan",
		Language:     "golang",
		Phase:        "phase-1-core",
		WorkPackages: []string{"WP-03"},
		Owns:         []string{"Route", "ServicePlan", "Calendar", "Timetable", "PlanVersion"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "WP-03"
const OwnershipSummary = "Route, ServicePlan, Calendar, Timetable, PlanVersion"
