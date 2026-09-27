package application

import (
	"fmt"

	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

// withinUnderwritingEnvelope refuses a catalogue entry whose cover is above the
// retention the release's carrier keeps for a single risk.
//
// The retention is contract data. It is renegotiated with the carrier and is not
// the same for two deployments, so the release declares it in minor units and
// the service reads it here instead of compiling a number in. A release that
// declares no retention sells the catalogue as published.
func (s *InsuranceService) withinUnderwritingEnvelope(product domain.InsuranceProduct) error {
	if s.retentionLimitMinor <= 0 || product.CoverageLimit.MinorUnits <= s.retentionLimitMinor {
		return nil
	}
	return fmt.Errorf("%w: requested cover is not available for this product", ErrDomainRule)
}
