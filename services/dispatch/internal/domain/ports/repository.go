package ports

import (
	"context"
	"time"

	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
)

type RideRequestRepository interface {
	Save(ctx context.Context, ride domain.RideRequest) error
	Update(ctx context.Context, ride domain.RideRequest) error
	FindByID(ctx context.Context, id domain.RideRequestID) (*domain.RideRequest, error)
	FindActiveByIntent(ctx context.Context, riderAccountID, intentFingerprint string) (*domain.RideRequest, error)
	FindPage(ctx context.Context, filter RideRequestListFilter) (RideRequestPage, error)
	FindTimedOut(ctx context.Context, now time.Time, requestTimeout, matchingTimeout time.Duration, limit int) ([]domain.RideRequest, error)
}

type RideRequestListFilter struct {
	RiderAccountID string
	Status         string
	Limit          int
	Offset         int
}
type RideRequestPage struct {
	Items []domain.RideRequest
	Total int
}
