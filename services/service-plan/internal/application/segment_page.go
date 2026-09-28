package application

import (
	"fmt"
	"strings"
)

// segmentPageBounds reports whether a segment listing may serve the page it was
// asked for. A caller reconciles a whole service by reading pages until it has
// seen total rows, so a page the deployment's listing bound does not cover is
// refused here rather than answered with a different page.
func segmentPageBounds(service string, limit, offset, listingBound int) error {
	if strings.TrimSpace(service) == "" {
		return fmt.Errorf("%w: scheduledServiceRef and valid pagination are required", ErrValidation)
	}
	if limit < 1 || limit > listingBound || offset < 0 {
		return fmt.Errorf("%w: scheduledServiceRef and valid pagination are required", ErrValidation)
	}
	return nil
}
