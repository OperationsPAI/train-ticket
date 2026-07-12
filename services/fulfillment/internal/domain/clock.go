package domain

import "time"

// timeNow is a package-level clock variable that can be overridden in tests.
var timeNow = time.Now
