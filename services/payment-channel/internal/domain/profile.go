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
	return ServiceProfile{ServiceID: "payment-channel", Domain: "Payment Channel", Language: "golang", Phase: "adr-0003-wave-a", WorkPackages: []string{"REQ-142"}, Owns: []string{"ChannelOrder", "ChannelRefund", "ChannelStatement", "ReconciliationDiscrepancy"}}
}
func Health() string { return "ok" }
