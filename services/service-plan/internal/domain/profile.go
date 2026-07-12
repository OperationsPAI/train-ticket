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
		WorkPackages: []string{"REQ-005"},
		Owns:         []string{"ServicePlan", "ServicePattern", "ServiceStop", "Calendar", "Timetable", "ScheduledService", "ServiceSegment", "PlanVersion"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "REQ-005"
const OwnershipSummary = "ServicePlan, ServicePattern, ServiceStop, Calendar, Timetable, ScheduledService, ServiceSegment, PlanVersion"
