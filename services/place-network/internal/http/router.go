package http

import (
	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/place-network/internal/application"
	"github.com/trainticket/greenfield/services/place-network/internal/domain"
	"github.com/trainticket/greenfield/services/place-network/internal/domain/ports"
)

func Router() *gin.Engine {
	profile := domain.Profile()
	idempotencyStore := idempotency.NewMemoryStore()
	service := application.NewService(application.ServiceConfig{
		Places:    ports.NewInMemoryPlaceRepository(),
		Nodes:     ports.NewInMemoryTransportNodeRepository(),
		Publisher: application.NewNoopPublisher(),
		Clock:     domain.RealClock{},
	})
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	NewHandler(service, idempotencyStore).RegisterRoutes(router)
	return router
}
