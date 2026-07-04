package http

import (
	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/supplier-catalog/internal/domain"
)

func Router() *gin.Engine {
	profile := domain.Profile()
	return goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    profile.ServiceID,
		Metadata:     profile,
		HealthStatus: domain.Health(),
		Observer:     goruntime.ObserverFromEnv(profile.ServiceID),
	})
}
