package http

import (
	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
	"github.com/trainticket/greenfield/services/provider-integration/internal/domain"
)

func Router() *gin.Engine {
	publisher := NoopPublisher{}
	service := application.NewInMemoryReservationService(publisher)
	return RouterWithDependencies(service, idempotency.NewMemoryStore())
}

func RouterWithDependencies(service application.ProviderReservationService, store idempotency.Store) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
	return router
}
