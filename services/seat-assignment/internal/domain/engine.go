package domain

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

type SeatPreference struct {
	PreferenceType string `json:"preferenceType"`
	Priority       int    `json:"priority"`
}
type AssignmentRequest struct {
	SegmentRef   string           `json:"segmentRef"`
	TravelerRefs []string         `json:"travelerRefs"`
	SeatClass    string           `json:"seatClass"`
	Preferences  []SeatPreference `json:"preferences"`
}

type AssignmentEngine struct{}

func NewAssignmentEngine() AssignmentEngine { return AssignmentEngine{} }

func (e AssignmentEngine) Assign(seatMap SeatMap, occupied []string, requests []AssignmentRequest, now time.Time) ([]SeatAssignment, error) {
	busy := map[string]struct{}{}
	for _, id := range occupied {
		busy[id] = struct{}{}
	}
	out := []SeatAssignment{}
	for _, req := range requests {
		if len(req.TravelerRefs) == 0 {
			continue
		}
		available := filterAvailable(seatMap.Seats, busy, normalizeClass(req.SeatClass))
		if len(available) < len(req.TravelerRefs) {
			return nil, fmt.Errorf("no matching seat available")
		}
		selected := []Seat{}
		if hasPreference(req.Preferences, PreferenceTogether) && len(req.TravelerRefs) > 1 {
			selected = findTogether(available, len(req.TravelerRefs), req.Preferences)
		}
		if len(selected) == 0 {
			selected = rankSeats(available, req.Preferences)[:len(req.TravelerRefs)]
		}
		for idx, seat := range selected {
			busy[seat.SeatId] = struct{}{}
			a, err := NewSeatAssignment("", req.SegmentRef, FormatDate(now), req.TravelerRefs[idx], seat.SeatId, NewID("hold"), now)
			if err != nil {
				return nil, err
			}
			out = append(out, *a)
		}
	}
	return out, nil
}

func (e AssignmentEngine) Choose(seatMap SeatMap, occupied []string, req AssignmentRequest) ([]Seat, error) {
	busy := map[string]struct{}{}
	for _, id := range occupied {
		busy[id] = struct{}{}
	}
	available := filterAvailable(seatMap.Seats, busy, normalizeClass(req.SeatClass))
	if len(available) < len(req.TravelerRefs) {
		return nil, fmt.Errorf("no matching seat available")
	}
	if hasPreference(req.Preferences, PreferenceTogether) && len(req.TravelerRefs) > 1 {
		if block := findTogether(available, len(req.TravelerRefs), req.Preferences); len(block) > 0 {
			return block, nil
		}
	}
	return rankSeats(available, req.Preferences)[:len(req.TravelerRefs)], nil
}

func filterAvailable(seats []Seat, busy map[string]struct{}, class string) []Seat {
	out := []Seat{}
	for _, seat := range seats {
		if _, ok := busy[seat.SeatId]; ok {
			continue
		}
		if class != "" && seat.SeatType != class {
			continue
		}
		out = append(out, seat)
	}
	return out
}
func normalizeClass(class string) string {
	switch strings.ToUpper(strings.TrimSpace(class)) {
	case "BUSINESS", ClassBusiness:
		return ClassBusiness
	case "FIRST", ClassFirst:
		return ClassFirst
	case "SECOND", "STANDARD", ClassSecond, "":
		return ClassSecond
	default:
		return strings.ToUpper(strings.TrimSpace(class))
	}
}
func hasPreference(prefs []SeatPreference, typ string) bool {
	for _, p := range prefs {
		if strings.EqualFold(p.PreferenceType, typ) {
			return true
		}
	}
	return false
}
func rankSeats(seats []Seat, prefs []SeatPreference) []Seat {
	out := append([]Seat(nil), seats...)
	sort.SliceStable(out, func(i, j int) bool {
		si, sj := score(out[i], prefs), score(out[j], prefs)
		if si == sj {
			return out[i].SeatId < out[j].SeatId
		}
		return si > sj
	})
	return out
}
func score(seat Seat, prefs []SeatPreference) int {
	total := 0
	for _, pref := range prefs {
		weight := 1
		if pref.Priority > 0 {
			weight = 10 - pref.Priority
			if weight < 1 {
				weight = 1
			}
		}
		switch strings.ToUpper(pref.PreferenceType) {
		case PreferenceWindow:
			if seat.Position == PositionWindow {
				total += 100 * weight
			}
		case PreferenceAisle:
			if seat.Position == PositionAisle {
				total += 90 * weight
			}
		case PreferenceQuietCar:
			if seat.CarNumber == 16 {
				total += 80 * weight
			}
		case PreferenceForwardFacing:
			if seat.Row%2 == 1 {
				total += 40 * weight
			}
		}
	}
	return total
}
func findTogether(seats []Seat, count int, prefs []SeatPreference) []Seat {
	byRow := map[string][]Seat{}
	for _, seat := range seats {
		key := fmt.Sprintf("%02d-%02d", seat.CarNumber, seat.Row)
		byRow[key] = append(byRow[key], seat)
	}
	blocks := [][]Seat{}
	for _, rowSeats := range byRow {
		sort.Slice(rowSeats, func(i, j int) bool { return rowSeats[i].Letter < rowSeats[j].Letter })
		if len(rowSeats) >= count {
			blocks = append(blocks, rowSeats[:count])
		}
	}
	if len(blocks) == 0 {
		return nil
	}
	sort.Slice(blocks, func(i, j int) bool { return blockScore(blocks[i], prefs) > blockScore(blocks[j], prefs) })
	return blocks[0]
}
func blockScore(seats []Seat, prefs []SeatPreference) int {
	total := 0
	for _, seat := range seats {
		total += score(seat, prefs)
	}
	total -= seats[0].CarNumber
	total -= seats[0].Row
	return total
}
