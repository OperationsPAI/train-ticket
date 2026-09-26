package application

import "github.com/trainticket/greenfield/services/fulfillment/internal/domain"

// boardingReaders is the vocabulary a boarding fact may arrive under: the
// readers whose evidence this branch was written to accept, in the order the
// service names them. A boarding fact names the reader it arrived from, and the
// command refuses a fact whose reader is not on the list.
var boardingReaders = []domain.FulfillmentSource{
	domain.FulfillmentSourceGate,
	domain.FulfillmentSourceStation,
	domain.FulfillmentSourceProvider,
	domain.FulfillmentSourceConductor,
	domain.FulfillmentSourceAdmin,
}

// acceptsBoardingSource reports whether a boarding fact may arrive under this
// source.
func acceptsBoardingSource(source domain.FulfillmentSource) bool {
	for _, reader := range boardingReaders {
		if reader == source {
			return true
		}
	}
	return false
}
