package main

import (
	"fmt"
	"math/rand"
	"sort"
	"strings"
	"time"
)

// Route topology: named lines, each an ordered list of stations with a real
// cumulative distance along the line.
//
// WHY THIS IS A GO TABLE AND NOT CONFIGURATION
//
// The station list is consumed twice: Bootstrap creates a place, a transport
// node and a scheduled service from it, and the persona demand model in
// providers.go picks trips out of it. A list duplicated across the two would
// drift, and the config file cannot be the single source for both because the
// YAML decoder here is non-strict: a mistyped nested key under a line decodes
// to a zero value, and a station at kilometre 0 would silently become a
// zero-distance trip that prices as the minimum fare. The table also carries an
// invariant the code relies on -- stops ordered by strictly increasing
// kilometre position -- which TestRailNetworkIsWellFormed enforces at build
// time rather than at the first search of a deployed run.
//
// What remains configuration is which lines are active
// (`bootstrap.lines`) and how much inventory to create on them
// (`bootstrap.services_per_date`). Those are load-shaping knobs. The geography
// is not.
//
// WHAT THE DOMAIN ACCEPTS, WHICH SHAPES ALL OF THIS
//
// service-plan's POST /api/v1/service-segments refuses any segment whose
// endpoints are not exactly its parent scheduled service's endpoints
// (serviceViewHasSegment in services/service-plan/internal/application/
// service.go). A partial segment on a longer service is a 422, so an
// intermediate stop is NOT expressible as a bookable segment: each bookable
// station pair needs its own scheduled service. trip-planning is single-leg
// only (every Itinerary it builds is `legs: vec![leg]`), so there are no
// connecting itineraries to fall back on either.
//
// Intermediate stops are therefore real in the topology -- Jinan West really
// is between Tianjin South and Nanjing South, and TripPair.Intermediate counts
// it -- and they lengthen the journey, through the dwell time a service spends
// at each one. They are not sent as a segment stop list, because no endpoint
// accepts one.
//
// DISTANCE IS DERIVABLE AND LOAD-BEARING
//
// Every stop carries its real cumulative kilometre position, so the distance of
// a trip is |km(dest) - km(origin)|: an actual distance, not an ordinal.
// fare-pricing's POST /api/v1/fare-quotes accepts `distanceKm`, and the
// default rule set installed on both the in-memory and the Postgres path
// (_install_default_rule_sets in services/fare-pricing/src/fare_pricing/api.py)
// carries per_km_rate 0.15 with a 500 km discount threshold. So a quote's price
// follows the distance instead of being one constant for every trip. Duration
// follows it too, through the departure/arrival times Bootstrap computes.

// Station is one station in the network. Codes are unique across the whole
// network and are what place-network's `code` field carries; place-network puts
// no constraint on the field, so these are the loadgen's own identifiers.
type Station struct {
	Code string
	Name string
}

// LineStop is one stop on one line, at a cumulative distance from that line's
// origin. A station shared by two lines appears once per line with the
// kilometre position each line measures it at: Changsha South is 1587 km along
// the Beijing-Guangzhou line and 1029 km along the Shanghai-Kunming line.
type LineStop struct {
	Station string // Station.Code
	KM      int    // cumulative kilometres from the line's first stop
}

// Line is a named railway line as an ordered sequence of stops. Order is the
// physical order along the line, which is what makes "origin before
// destination" meaningful and the stops between two of them real.
type Line struct {
	Name string
	// AvgSpeedKMH is the line's average running speed excluding station dwell
	// time. Used to derive a journey duration from a distance, so an 1318 km
	// trip is not scheduled to take the same time as a 131 km one.
	AvgSpeedKMH int
	Stops       []LineStop
}

// stations is every station in the network, by code.
var stations = map[string]Station{
	// Beijing-Shanghai
	"BJN": {"BJN", "Beijing South"},
	"LFG": {"LFG", "Langfang"},
	"TJS": {"TJS", "Tianjin South"},
	"DZD": {"DZD", "Dezhou East"},
	"JNX": {"JNX", "Jinan West"},
	"QFD": {"QFD", "Qufu East"},
	"TZD": {"TZD", "Tengzhou East"},
	"XZD": {"XZD", "Xuzhou East"},
	"SXD": {"SXD", "Suzhou East"},
	"BBN": {"BBN", "Bengbu South"},
	"NJN": {"NJN", "Nanjing South"},
	"ZJN": {"ZJN", "Zhenjiang South"},
	"CZB": {"CZB", "Changzhou North"},
	"WXD": {"WXD", "Wuxi East"},
	"SZB": {"SZB", "Suzhou North"},
	"SHQ": {"SHQ", "Shanghai Hongqiao"},

	// Beijing-Guangzhou
	"BJX": {"BJX", "Beijing West"},
	"BDD": {"BDD", "Baoding East"},
	"SJZ": {"SJZ", "Shijiazhuang"},
	"XTD": {"XTD", "Xingtai East"},
	"HDD": {"HDD", "Handan East"},
	"AYD": {"AYD", "Anyang East"},
	"ZZD": {"ZZD", "Zhengzhou East"},
	"LHX": {"LHX", "Luohe West"},
	"ZMX": {"ZMX", "Zhumadian West"},
	"XYD": {"XYD", "Xinyang East"},
	"WHN": {"WHN", "Wuhan"},
	"YYD": {"YYD", "Yueyang East"},
	"CSN": {"CSN", "Changsha South"},
	"HYD": {"HYD", "Hengyang East"},
	"SGN": {"SGN", "Shaoguan"},
	"GZN": {"GZN", "Guangzhou South"},

	// Shanghai-Kunming
	"JXN": {"JXN", "Jiaxing South"},
	"HZD": {"HZD", "Hangzhou East"},
	"ZJI": {"ZJI", "Zhuji"},
	"YWU": {"YWU", "Yiwu"},
	"JHA": {"JHA", "Jinhua"},
	"SRO": {"SRO", "Shangrao"},
	"YTB": {"YTB", "Yingtan North"},
	"NCX": {"NCX", "Nanchang West"},
	"XYB": {"XYB", "Xinyu North"},
	"PXB": {"PXB", "Pingxiang North"},
	"LDN": {"LDN", "Loudi South"},
	"HHN": {"HHN", "Huaihua South"},
	"GYB": {"GYB", "Guiyang North"},
	"ASX": {"ASX", "Anshun West"},
	"QJB": {"QJB", "Qujing North"},
	"KMN": {"KMN", "Kunming South"},

	// Hangzhou-Shenzhen
	"SXB":  {"SXB", "Shaoxing North"},
	"NBO":  {"NBO", "Ningbo"},
	"TZH":  {"TZH", "Taizhou"},
	"WZN":  {"WZN", "Wenzhou South"},
	"NDE":  {"NDE", "Ningde"},
	"FZU":  {"FZU", "Fuzhou"},
	"PTN":  {"PTN", "Putian"},
	"QZU":  {"QZU", "Quanzhou"},
	"XMB":  {"XMB", "Xiamen North"},
	"ZZU":  {"ZZU", "Zhangzhou"},
	"CSA":  {"CSA", "Chaoshan"},
	"SWI":  {"SWI", "Shanwei"},
	"HZN":  {"HZN", "Huizhou South"},
	"SZB2": {"SZB2", "Shenzhen North"},
}

// railNetwork is the topology. Kilometre positions are the real cumulative
// distances of these lines, so the fare and duration a trip derives from them
// are the fare and duration of a real journey.
//
// The lines intersect on purpose: Shanghai Hongqiao ends Beijing-Shanghai and
// starts Shanghai-Kunming, Changsha South is on both Beijing-Guangzhou and
// Shanghai-Kunming, and Hangzhou East is on both Shanghai-Kunming and
// Hangzhou-Shenzhen. Those shared stations are what make this a network rather
// than four disjoint corridors, and they are the stations a persona weighting
// trunk lines will concentrate on.
var railNetwork = []Line{
	{
		Name:        "beijing-shanghai",
		AvgSpeedKMH: 300,
		Stops: []LineStop{
			{"BJN", 0}, {"LFG", 59}, {"TJS", 131}, {"DZD", 327}, {"JNX", 419},
			{"QFD", 533}, {"TZD", 588}, {"XZD", 688}, {"SXD", 794}, {"BBN", 862},
			{"NJN", 1023}, {"ZJN", 1092}, {"CZB", 1144}, {"WXD", 1201},
			{"SZB", 1227}, {"SHQ", 1318},
		},
	},
	{
		Name:        "beijing-guangzhou",
		AvgSpeedKMH: 295,
		Stops: []LineStop{
			{"BJX", 0}, {"BDD", 146}, {"SJZ", 281}, {"XTD", 384}, {"HDD", 437},
			{"AYD", 505}, {"ZZD", 695}, {"LHX", 818}, {"ZMX", 894}, {"XYD", 1005},
			{"WHN", 1229}, {"YYD", 1424}, {"CSN", 1587}, {"HYD", 1770},
			{"SGN", 2003}, {"GZN", 2298},
		},
	},
	{
		Name:        "shanghai-kunming",
		AvgSpeedKMH: 275,
		Stops: []LineStop{
			{"SHQ", 0}, {"JXN", 84}, {"HZD", 159}, {"ZJI", 216}, {"YWU", 258},
			{"JHA", 297}, {"SRO", 448}, {"YTB", 528}, {"NCX", 610}, {"XYB", 717},
			{"PXB", 813}, {"CSN", 1029}, {"LDN", 1131}, {"HHN", 1341},
			{"GYB", 1620}, {"ASX", 1712}, {"QJB", 2020}, {"KMN", 2252},
		},
	},
	{
		Name:        "hangzhou-shenzhen",
		AvgSpeedKMH: 235,
		Stops: []LineStop{
			{"HZD", 0}, {"SXB", 51}, {"NBO", 149}, {"TZH", 232}, {"WZN", 346},
			{"NDE", 480}, {"FZU", 578}, {"PTN", 674}, {"QZU", 743}, {"XMB", 823},
			{"ZZU", 870}, {"CSA", 1028}, {"SWI", 1160}, {"HZN", 1284},
			{"SZB2", 1381},
		},
	},
}

// dwellMinutesPerStop is the time a service spends at each intermediate stop it
// passes. Real intermediate stops are what make two trips of the same distance
// on different parts of a line take different times.
const dwellMinutesPerStop = 2

// ActiveLines resolves the configured line names to lines, in the table's
// order. An empty selection means the whole network.
//
// An unknown name is a hard error rather than a silently dropped line: the
// selection is a list of strings against a Go table, and a typo there would
// remove a quarter of the offered demand while the run looked healthy.
func ActiveLines(names []string) ([]Line, error) {
	if len(names) == 0 {
		return railNetwork, nil
	}
	wanted := make(map[string]bool, len(names))
	for _, n := range names {
		wanted[strings.TrimSpace(n)] = true
	}
	var out []Line
	for _, line := range railNetwork {
		if wanted[line.Name] {
			out = append(out, line)
			delete(wanted, line.Name)
		}
	}
	if len(wanted) > 0 {
		unknown := make([]string, 0, len(wanted))
		for n := range wanted {
			unknown = append(unknown, n)
		}
		return nil, fmt.Errorf("bootstrap.lines names no line in the topology: %s",
			strings.Join(unknown, ", "))
	}
	return out, nil
}

// mustDate parses a YYYY-MM-DD date as midnight UTC.
//
// Panics on a date it cannot parse. Every caller passes a date from
// ComputeDepartureDates or from bootstrap.departure_dates, so an unparseable
// one means the config carries a malformed date: creating a service at the
// zero time instead would seed a 1970 departure that every later search
// silently fails to find.
func mustDate(date string) time.Time {
	t, err := time.Parse("2006-01-02", date)
	if err != nil {
		panic(fmt.Sprintf("departure date %q is not YYYY-MM-DD: %v", date, err))
	}
	return t
}

// StationName returns a station's display name, or its code when the code is
// not in the table.
func StationName(code string) string {
	if s, ok := stations[code]; ok {
		return s.Name
	}
	return code
}

// Stations returns every distinct station on the given lines, in the order the
// lines visit them. Order is deterministic so Bootstrap creates places in a
// stable sequence across restarts.
func Stations(lines []Line) []Station {
	seen := make(map[string]bool)
	var out []Station
	for _, line := range lines {
		for _, stop := range line.Stops {
			if seen[stop.Station] {
				continue
			}
			seen[stop.Station] = true
			out = append(out, stations[stop.Station])
		}
	}
	return out
}

// TripPair is one bookable trip: two stations on a common line, the origin
// before the destination in the line's own direction of travel, with the real
// distance between them and the stops a service would pass through.
type TripPair struct {
	Line         string
	OriginCode   string
	DestCode     string
	DistanceKM   int
	Intermediate int // number of stops strictly between origin and destination
}

// DurationMinutes is how long this trip takes: the running time at the line's
// average speed, plus dwell time at every intermediate stop. This is what makes
// a service's arrival time follow its distance instead of being a random hour
// of the evening.
func (t TripPair) DurationMinutes(speedKMH int) int {
	if speedKMH <= 0 {
		speedKMH = 250
	}
	running := t.DistanceKM * 60 / speedKMH
	return running + t.Intermediate*dwellMinutesPerStop
}

// LinePairs returns every ordered station pair on a line, in both directions.
//
// Both directions, because a line is bidirectional track and a traveller who
// goes Beijing to Shanghai comes back. "Origin before destination" is a
// statement about the pair being a real trip along the line, which a reversed
// pair equally is; what it rules out is a pair of stations with no line in
// common, which is what the previous uniform draw over four city codes
// produced.
func LinePairs(line Line) []TripPair {
	n := len(line.Stops)
	out := make([]TripPair, 0, n*(n-1))
	for i := 0; i < n; i++ {
		for j := 0; j < n; j++ {
			if i == j {
				continue
			}
			lo, hi := i, j
			if lo > hi {
				lo, hi = hi, lo
			}
			out = append(out, TripPair{
				Line:         line.Name,
				OriginCode:   line.Stops[i].Station,
				DestCode:     line.Stops[j].Station,
				DistanceKM:   line.Stops[hi].KM - line.Stops[lo].KM,
				Intermediate: hi - lo - 1,
			})
		}
	}
	return out
}

// InventoryPairs picks the station pairs Bootstrap should create services for
// on one date.
//
// Spread across distance rather than sampled uniformly. A uniform draw over all
// ordered pairs is dominated by long trips -- on a 16-stop line most pairs are
// far apart -- so a persona that wants a 200 km hop would find nothing to book.
// This walks the distance-sorted pair list at an even stride, so the created
// inventory covers the whole distance spectrum of every active line and every
// persona's preferred band has something in it.
//
// perDate is divided across the active lines, so adding a line widens the
// network at a fixed total inventory cost rather than multiplying it.
func InventoryPairs(lines []Line, perDate int, rng *rand.Rand) []TripPair {
	if len(lines) == 0 || perDate <= 0 {
		return nil
	}
	perLine := perDate / len(lines)
	if perLine < 1 {
		perLine = 1
	}

	var out []TripPair
	for _, line := range lines {
		pairs := LinePairs(line)
		sortPairsByDistance(pairs)
		if len(pairs) <= perLine {
			out = append(out, pairs...)
			continue
		}
		stride := float64(len(pairs)) / float64(perLine)
		for k := 0; k < perLine; k++ {
			// Jitter inside the stride so successive dates do not all get the
			// identical pair set, while the spread over distance is preserved.
			idx := int(float64(k)*stride) + rng.Intn(int(stride)+1)
			if idx >= len(pairs) {
				idx = len(pairs) - 1
			}
			out = append(out, pairs[idx])
		}
	}
	return out
}

// sortPairsByDistance orders pairs by distance, then by origin and destination
// code so the stride below lands on the same pairs for a given line rather
// than on whatever order equal distances happened to be generated in.
func sortPairsByDistance(pairs []TripPair) {
	sort.Slice(pairs, func(i, j int) bool {
		a, b := pairs[i], pairs[j]
		if a.DistanceKM != b.DistanceKM {
			return a.DistanceKM < b.DistanceKM
		}
		if a.OriginCode != b.OriginCode {
			return a.OriginCode < b.OriginCode
		}
		return a.DestCode < b.DestCode
	})
}
