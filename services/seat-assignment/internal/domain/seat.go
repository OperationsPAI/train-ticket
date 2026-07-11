package domain

import "fmt"

type Seat struct {
	SeatId    string `json:"seatId"`
	CarNumber int    `json:"carNumber"`
	Row       int    `json:"row"`
	Letter    string `json:"letter"`
	SeatType  string `json:"seatType"`
	Position  string `json:"position"`
}

type SeatMap struct {
	TrainType string `json:"trainType"`
	Seats     []Seat `json:"seats"`
}

func NewSeatMap(config TrainConfig) SeatMap {
	seats := make([]Seat, 0)
	for _, car := range config.Cars {
		letters := lettersForLayout(car.Layout)
		for row := 1; row <= car.Rows; row++ {
			for _, letter := range letters {
				seats = append(seats, Seat{SeatId: fmt.Sprintf("%02d-%02d%s", car.CarNumber, row, letter), CarNumber: car.CarNumber, Row: row, Letter: letter, SeatType: car.CarType, Position: positionFor(car.Layout, letter)})
			}
		}
	}
	return SeatMap{TrainType: config.TrainType, Seats: seats}
}

func DefaultSeatMap() SeatMap { return NewSeatMap(DefaultCRH380AConfig()) }

func lettersForLayout(layout string) []string {
	switch layout {
	case "2+1":
		return []string{"A", "B", "C"}
	case "2+2":
		return []string{"A", "B", "C", "D"}
	default:
		return []string{"A", "B", "C", "D", "E"}
	}
}

func positionFor(layout, letter string) string {
	switch layout {
	case "2+2":
		if letter == "A" || letter == "D" {
			return PositionWindow
		}
		return PositionAisle
	case "2+1":
		if letter == "A" || letter == "C" {
			return PositionWindow
		}
		return PositionAisle
	default:
		if letter == "A" || letter == "E" {
			return PositionWindow
		}
		if letter == "C" || letter == "D" {
			return PositionAisle
		}
		return PositionMiddle
	}
}

func (m SeatMap) Find(id string) (Seat, bool) {
	for _, seat := range m.Seats {
		if seat.SeatId == id {
			return seat, true
		}
	}
	return Seat{}, false
}
