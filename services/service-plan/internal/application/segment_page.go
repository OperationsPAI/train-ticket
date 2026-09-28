package application

import (
	"fmt"
	"strings"
)

// segmentPageBounds reports whether a segment listing may serve the page it was
// asked for. The bound is the one the release resolved; every read path of a
// listing goes through this check, so the listing and its repository agree on
// the page they answer with.
func segmentPageBounds(service string, limit, offset, listingBound int) error {
	if strings.TrimSpace(service) == "" {
		return fmt.Errorf("%w: scheduledServiceRef and valid pagination are required", ErrValidation)
	}
	if limit < 1 || limit > listingBound || offset < 0 {
		return fmt.Errorf("%w: scheduledServiceRef and valid pagination are required", ErrValidation)
	}
	return nil
}
