package http

import (
	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/provider-integration/internal/application"
	"github.com/trainticket/greenfield/services/provider-integration/internal/domain"
)

func Router() *gin.Engine {
	publisher := NoopPublisher{}
	service := application.NewInMemoryReservationService(publisher)
	return RouterWithDependencies(service, application.NewIdempotencyStore())
}

func RouterWithDependencies(service application.ProviderReservationService, idempotency *application.IdempotencyStore) *gin.Engine {
	profile := domain.Profile()
	router := goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
	NewHandler(service, idempotency).Register(router)
	return router
}
