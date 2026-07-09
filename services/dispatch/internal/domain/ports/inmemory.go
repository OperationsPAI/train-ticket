package ports

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
)

type InMemoryRideRequestRepository struct {
	mu    sync.RWMutex
	rides map[domain.RideRequestID]domain.RideRequest
	seq   map[domain.RideRequestID]int64
	next  int64
}

func NewInMemoryRideRequestRepository() *InMemoryRideRequestRepository {
	return &InMemoryRideRequestRepository{rides: map[domain.RideRequestID]domain.RideRequest{}, seq: map[domain.RideRequestID]int64{}}
}
func (r *InMemoryRideRequestRepository) Save(_ context.Context, ride domain.RideRequest) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.rides[ride.ID]; ok {
		return fmt.Errorf("ride request already exists")
	}
	for _, existing := range r.rides {
		if existing.Active() && existing.RiderAccountID == ride.RiderAccountID && existing.IntentFingerprint == ride.IntentFingerprint {
			return fmt.Errorf("active dispatch already exists")
		}
	}
	ride.Version = 1
	r.rides[ride.ID] = ride
	r.seq[ride.ID] = r.next
	r.next++
	return nil
}
func (r *InMemoryRideRequestRepository) Update(_ context.Context, ride domain.RideRequest) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.rides[ride.ID]; !ok {
		return fmt.Errorf("ride request not found")
	}
	ride.Version++
	r.rides[ride.ID] = ride
	return nil
}
func (r *InMemoryRideRequestRepository) FindByID(_ context.Context, id domain.RideRequestID) (*domain.RideRequest, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	ride, ok := r.rides[id]
	if !ok {
		return nil, nil
	}
	return &ride, nil
}
func (r *InMemoryRideRequestRepository) FindActiveByIntent(_ context.Context, rider, fp string) (*domain.RideRequest, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, ride := range r.rides {
		if ride.Active() && ride.RiderAccountID == strings.TrimSpace(rider) && ride.IntentFingerprint == strings.TrimSpace(fp) {
			cp := ride
			return &cp, nil
		}
	}
	return nil, nil
}
func (r *InMemoryRideRequestRepository) FindPage(_ context.Context, f RideRequestListFilter) (RideRequestPage, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	items := []domain.RideRequest{}
	for _, ride := range r.rides {
		if ride.RiderAccountID != f.RiderAccountID {
			continue
		}
		if f.Status != "" && string(ride.Status) != f.Status {
			continue
		}
		items = append(items, ride)
	}
	sort.Slice(items, func(i, j int) bool { return r.seq[items[i].ID] < r.seq[items[j].ID] })
	total := len(items)
	if f.Offset > total {
		f.Offset = total
	}
	end := f.Offset + f.Limit
	if end > total {
		end = total
	}
	return RideRequestPage{Items: append([]domain.RideRequest(nil), items[f.Offset:end]...), Total: total}, nil
}
func (r *InMemoryRideRequestRepository) FindTimedOut(_ context.Context, now time.Time, requestTimeout, matchingTimeout time.Duration, limit int) ([]domain.RideRequest, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := []domain.RideRequest{}
	for _, ride := range r.rides {
		if len(out) >= limit {
			break
		}
		if (ride.Status == domain.StatusRequested && now.Sub(ride.UpdatedAt) > requestTimeout) || (ride.Status == domain.StatusMatching && now.Sub(ride.UpdatedAt) > matchingTimeout) {
			out = append(out, ride)
		}
	}
	return out, nil
}
