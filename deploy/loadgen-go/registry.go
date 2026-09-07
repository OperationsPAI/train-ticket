package main

import (
	"encoding/json"
	"math/rand"
	"os"
	"sync"
)

// Purchase represents a completed purchase in the registry.
type Purchase struct {
	Order            string `json:"order"`
	Saga             string `json:"saga"`
	SB               string `json:"sb"`
	Seg              string `json:"seg"`
	Traveler         string `json:"traveler"`
	Account          string `json:"account"`
	Entitlement      string `json:"entitlement"`
	TotalMinor       int    `json:"total_minor"`
	Status           string `json:"status"`
	Offer            string `json:"offer"`
	PaymentIntent    string `json:"payment_intent"`
	Itinerary        string `json:"itinerary"`
	Quote            string `json:"quote"`
	PostSalesCase    string `json:"post_sales_case"`
	FulfillmentRecord string `json:"fulfillment_record"`
	JourneyDate      string `json:"journey_date"`

	// Refs to entities created by the optional purchase-journey branches.
	// These exist so the long-tail read probes can GET *real*, previously
	// created ids -- probing a synthetic id would only ever exercise the
	// 404 path, not the cache-cold read path these probes are for. They are
	// persisted with the rest of the purchase so probes still find real
	// entities after a loadgen restart.
	AncillaryCatalog   string `json:"ancillary_catalog,omitempty"`
	AncillaryOffer     string `json:"ancillary_offer,omitempty"`
	AncillaryOrderItem string `json:"ancillary_order_item,omitempty"`
	InvoiceTitle       string `json:"invoice_title,omitempty"`
	InvoiceRequest     string `json:"invoice_request,omitempty"`
	Invoice            string `json:"invoice,omitempty"`
	Benefit            string `json:"benefit,omitempty"`
	WalletAccount      string `json:"wallet_account,omitempty"`

	// Search-derived refs, retained for the place-network / service-plan
	// probes that the previous implementation drove off the same journey.
	Service     string `json:"service,omitempty"`
	OriginPlace string `json:"origin_place,omitempty"`
	OriginNode  string `json:"origin_node,omitempty"`
}

// WaitlistRef tracks a waitlist request.
type WaitlistRef struct {
	WaitlistRequestID string `json:"waitlist_request_id"`
	Account           string `json:"account"`
	Traveler          string `json:"traveler"`
	Seg               string `json:"seg"`
	PaymentIntent     string `json:"payment_intent"`
	IntentFingerprint string `json:"intent_fingerprint"`
	Status            string `json:"status"`
}

// AccountEntry represents a registered account with its travelers.
type AccountEntry struct {
	AccountID        string   `json:"account_id"`
	Travelers        []string `json:"travelers"`
	IdentityVerified []string `json:"identity_verified,omitempty"`
}

// RouteEntry represents a known bookable route.
type RouteEntry struct {
	OriginPlace      string `json:"origin_place"`
	DestPlace        string `json:"dest_place"`
	Date             string `json:"date"`
	ServiceNumber    string `json:"service_number,omitempty"`
	ScheduledService string `json:"scheduled_service,omitempty"`
	OriginNode       string `json:"origin_node,omitempty"`
	DestNode         string `json:"dest_node,omitempty"`
}

// Registry is the shared state: accounts, purchases, work queues.
type Registry struct {
	mu sync.Mutex

	Accounts     []*AccountEntry      `json:"accounts"`
	Purchases    []*Purchase           `json:"purchases"`
	Waitlists    []*WaitlistRef        `json:"waitlists"`
	Routes       []*RouteEntry         `json:"routes"`
	Places       map[string]string     `json:"places"`
	OpsEntities  map[string][]string   `json:"ops_entities"`
	InvoiceTitles map[string]string    `json:"invoice_titles"`

	// transferStationNode caches the place-network transport node seeded for the
	// transfer journey. transfer-management resolves fromNodeRef/toNodeRef
	// against place-network and 422s on an unknown node, so this must be a real
	// nodeId. Runtime-only and deliberately NOT persisted: a node id that
	// outlived a place-network reset would 404 and wedge the journey forever.
	transferStationMu   sync.Mutex
	transferStationNode string

	// Staff work queues (runtime only, not persisted)
	QReservation chan *WorkItem
	QTicketing   chan *WorkItem
	QRisk        chan *WorkItem
	QSupport     chan *WorkItem
	QDispatch    chan *WorkItem
}

// WorkItem represents a staff work queue item.
type WorkItem struct {
	Kind    string
	Order   string
	Seg     string
	Traveler string
	SB      string
	Ride    string
	Branch  string
	Case    string
	Requester string

	// Results filled by staff
	mu       sync.Mutex
	done     chan struct{}
	Results  map[string]interface{}
	Failed   bool
	ErrorMsg string
}

func NewWorkItem(kind string) *WorkItem {
	return &WorkItem{
		Kind:    kind,
		done:    make(chan struct{}),
		Results: make(map[string]interface{}),
	}
}

func (w *WorkItem) SetResult(key string, value interface{}) {
	w.mu.Lock()
	w.Results[key] = value
	w.mu.Unlock()
}

func (w *WorkItem) GetResult(key string) (interface{}, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	v, ok := w.Results[key]
	return v, ok
}

func (w *WorkItem) SetFailed(msg string) {
	w.mu.Lock()
	w.Failed = true
	w.ErrorMsg = msg
	w.mu.Unlock()
	select {
	case <-w.done:
	default:
		close(w.done)
	}
}

func (w *WorkItem) Complete() {
	select {
	case <-w.done:
	default:
		close(w.done)
	}
}

func (w *WorkItem) Done() <-chan struct{} {
	return w.done
}

func NewRegistry() *Registry {
	return &Registry{
		Accounts:      make([]*AccountEntry, 0),
		Purchases:     make([]*Purchase, 0),
		Waitlists:     make([]*WaitlistRef, 0),
		Routes:        make([]*RouteEntry, 0),
		Places:        make(map[string]string),
		OpsEntities:   map[string][]string{"suppliers": {}, "carriers": {}, "contracts": {}},
		InvoiceTitles: make(map[string]string),
		QReservation:  make(chan *WorkItem, 1000),
		QTicketing:    make(chan *WorkItem, 1000),
		QRisk:         make(chan *WorkItem, 1000),
		QSupport:      make(chan *WorkItem, 1000),
		QDispatch:     make(chan *WorkItem, 1000),
	}
}

func LoadRegistry(path string) *Registry {
	reg := NewRegistry()
	if path == "" {
		return reg
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return reg
	}
	var raw struct {
		Accounts     []*AccountEntry    `json:"accounts"`
		Purchases    []*Purchase        `json:"purchases"`
		Waitlists    []*WaitlistRef     `json:"waitlists"`
		Routes       []*RouteEntry      `json:"routes"`
		Places       map[string]string  `json:"places"`
		OpsEntities  map[string][]string `json:"ops_entities"`
		InvoiceTitles map[string]string  `json:"invoice_titles"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return reg
	}
	if raw.Accounts != nil {
		reg.Accounts = raw.Accounts
	}
	if raw.Purchases != nil {
		reg.Purchases = raw.Purchases
	}
	if raw.Waitlists != nil {
		reg.Waitlists = raw.Waitlists
	}
	if raw.Routes != nil {
		reg.Routes = raw.Routes
	}
	if raw.Places != nil {
		reg.Places = raw.Places
	}
	if raw.OpsEntities != nil {
		reg.OpsEntities = raw.OpsEntities
	}
	if raw.InvoiceTitles != nil {
		reg.InvoiceTitles = raw.InvoiceTitles
	}
	return reg
}

func (r *Registry) Save(path string) {
	if path == "" {
		return
	}
	r.mu.Lock()
	data, _ := json.Marshal(struct {
		Accounts      []*AccountEntry     `json:"accounts"`
		Purchases     []*Purchase         `json:"purchases"`
		Waitlists     []*WaitlistRef      `json:"waitlists"`
		Routes        []*RouteEntry       `json:"routes"`
		Places        map[string]string   `json:"places"`
		OpsEntities   map[string][]string `json:"ops_entities"`
		InvoiceTitles map[string]string   `json:"invoice_titles"`
	}{
		Accounts:      r.Accounts,
		Purchases:     r.Purchases,
		Waitlists:     r.Waitlists,
		Routes:        r.Routes,
		Places:        r.Places,
		OpsEntities:   r.OpsEntities,
		InvoiceTitles: r.InvoiceTitles,
	})
	r.mu.Unlock()

	tmp := path + ".tmp"
	_ = os.WriteFile(tmp, data, 0644)
	_ = os.Rename(tmp, path)
}

func (r *Registry) PickAccount(rng *rand.Rand) *AccountEntry {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.Accounts) == 0 {
		return nil
	}
	return r.Accounts[rng.Intn(len(r.Accounts))]
}

func (r *Registry) AddAccount(accountID string) *AccountEntry {
	r.mu.Lock()
	defer r.mu.Unlock()
	entry := &AccountEntry{AccountID: accountID, Travelers: []string{}}
	r.Accounts = append(r.Accounts, entry)
	if len(r.Accounts) > 500 {
		r.Accounts = r.Accounts[1:]
	}
	return entry
}

func (r *Registry) AddPurchase(p *Purchase) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.Purchases = append(r.Purchases, p)
	if len(r.Purchases) > 1000 {
		r.Purchases = r.Purchases[1:]
	}
}

func (r *Registry) TakePurchase(rng *rand.Rand, status string) *Purchase {
	r.mu.Lock()
	defer r.mu.Unlock()
	var candidates []*Purchase
	for _, p := range r.Purchases {
		if p.Status == status {
			candidates = append(candidates, p)
		}
	}
	if len(candidates) == 0 {
		return nil
	}
	p := candidates[rng.Intn(len(candidates))]
	p.Status = "consumed"
	return p
}

func (r *Registry) ReleasePurchase(p *Purchase, status string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	p.Status = status
}

func (r *Registry) AddWaitlist(w *WaitlistRef) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.Waitlists = append(r.Waitlists, w)
	if len(r.Waitlists) > 500 {
		r.Waitlists = r.Waitlists[1:]
	}
}

func (r *Registry) RememberOpsEntity(kind, entityID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	bucket := r.OpsEntities[kind]
	bucket = append(bucket, entityID)
	if len(bucket) > 200 {
		bucket = bucket[1:]
	}
	r.OpsEntities[kind] = bucket
}

func (r *Registry) GetRoutes() []*RouteEntry {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]*RouteEntry, len(r.Routes))
	copy(out, r.Routes)
	return out
}

// CachedInvoiceTitle returns the invoice title id previously created for an
// account, if any. The caller re-validates it against invoicing before use --
// a title that was deactivated (or lost to a service reset) must not be reused,
// because request_invoice 412s on a non-ACTIVE title.
func (r *Registry) CachedInvoiceTitle(accountID string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.InvoiceTitles[accountID]
}

// RememberInvoiceTitle caches an account's invoice title id.
func (r *Registry) RememberInvoiceTitle(accountID, titleID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.InvoiceTitles == nil {
		r.InvoiceTitles = make(map[string]string)
	}
	r.InvoiceTitles[accountID] = titleID
}

func (r *Registry) PickPurchaseForRead(rng *rand.Rand) *Purchase {
	r.mu.Lock()
	defer r.mu.Unlock()
	var candidates []*Purchase
	for _, p := range r.Purchases {
		if p.Status != "consumed" {
			candidates = append(candidates, p)
		}
	}
	if len(candidates) == 0 {
		return nil
	}
	return candidates[rng.Intn(len(candidates))]
}
