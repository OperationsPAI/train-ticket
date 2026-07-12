package domain

type TrainConfig struct {
	TrainType string      `json:"trainType"`
	Cars      []CarConfig `json:"cars"`
}

type CarConfig struct {
	CarNumber int      `json:"carNumber"`
	CarType   string   `json:"carType"`
	Layout    string   `json:"layout"`
	Rows      int      `json:"rows"`
	Features  []string `json:"features"`
}

func DefaultCRH380AConfig() TrainConfig {
	cars := []CarConfig{{CarNumber: 1, CarType: ClassBusiness, Layout: "2+1", Rows: 12, Features: []string{"POWER_OUTLET", "WIFI", "RECLINER"}}}
	for car := 2; car <= 5; car++ {
		cars = append(cars, CarConfig{CarNumber: car, CarType: ClassFirst, Layout: "2+2", Rows: 20, Features: []string{"POWER_OUTLET"}})
	}
	for car := 6; car <= 16; car++ {
		features := []string{}
		if car == 16 {
			features = []string{"QUIET_CAR"}
		}
		cars = append(cars, CarConfig{CarNumber: car, CarType: ClassSecond, Layout: "3+2", Rows: 25, Features: features})
	}
	return TrainConfig{TrainType: "CRH380A", Cars: cars}
}
