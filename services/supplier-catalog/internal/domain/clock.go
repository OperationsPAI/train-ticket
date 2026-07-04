package domain

import "time"

// timeNow is a package-level clock variable that can be overridden in tests
// to make time-based behaviour deterministic.
var timeNow = time.Now
