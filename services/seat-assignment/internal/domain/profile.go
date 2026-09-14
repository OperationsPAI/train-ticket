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
		ServiceID:    "seat-assignment",
		Domain:       "Seat Assignment",
		Language:     "golang",
		Phase:        "phase-1-activation",
		WorkPackages: []string{"REQ-304"},
		Owns:         []string{"TrainConfig", "SeatInventory", "SeatAssignment"},
	}
}

func Health() string {
	return "ok"
}

const WorkPackageSummary = "REQ-304"
const OwnershipSummary = "TrainConfig, SeatInventory, SeatAssignment"
