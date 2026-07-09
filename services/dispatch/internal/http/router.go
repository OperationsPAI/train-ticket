package http

import (
	"github.com/gin-gonic/gin"
	"github.com/trainticket/greenfield/platform/go-kit/idempotency"
	goruntime "github.com/trainticket/greenfield/platform/go-runtime"
	"github.com/trainticket/greenfield/services/dispatch/internal/application"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
	"github.com/trainticket/greenfield/services/dispatch/internal/domain/ports"
)

func Router() *gin.Engine {
	profile := domain.Profile()
	service := application.NewService(application.ServiceConfig{Rides: ports.NewInMemoryRideRequestRepository(), Publisher: application.NewNoopPublisher(), Clock: domain.RealClock{}})
	router := goruntime.NewGinRouter(goruntime.GinConfig{ServiceID: profile.ServiceID, Metadata: profile, HealthStatus: domain.Health(), Observer: goruntime.ObserverFromEnv(profile.ServiceID)})
	NewHandler(service, idempotency.NewMemoryStore()).RegisterRoutes(router)
	return router
}
