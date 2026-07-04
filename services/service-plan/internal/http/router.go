package http

import (
	"github.com/gin-gonic/gin"

	goruntime "github.com/trainticket/greenfield/platform/go-runtime"

	"github.com/trainticket/greenfield/services/service-plan/internal/domain"
)

func Router() *gin.Engine {
	return goruntime.NewGinRouter(goruntime.GinConfig{
		ServiceID:    domain.Profile().ServiceID,
		Metadata:     domain.Profile(),
		HealthStatus: domain.Health(),
	})
}
