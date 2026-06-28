package http

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/trainticket/greenfield/services/dispatch/internal/domain"
)

func Router() *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.GET("/health", func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, gin.H{"status": domain.Health(), "service": domain.Profile()})
	})
	return router
}
