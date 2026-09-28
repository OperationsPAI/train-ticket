package application

import (
	"fmt"

	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

// rejectAboveRetention refuses a catalogue entry whose cover is above the
// retention the release's carrier keeps for a single risk.
//
// The retention is contract data. It is renegotiated with the carrier and is not
// the same for two deployments, so the release declares it in minor units and
// the service reads it here instead of compiling a number in. A release that
// declares no retention sells the catalogue as published.
func (s *InsuranceService) rejectAboveRetention(product domain.InsuranceProduct) error {
	if product.WithinRetention(s.retentionLimitMinor) {
		return nil
	}
	return fmt.Errorf("%w: insurance is not available for this purchase", ErrDomainRule)
}
