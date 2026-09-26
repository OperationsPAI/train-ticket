package messaging

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

// filingIdentityPrefix keys the identities the catalogue has filed in the shared
// cache. The cache is shared with the other adapter instances of the release, so a
// repeat arrival is refused whichever replica answers it.
const filingIdentityPrefix = "supplier-catalog:filed-identity:"

// FilingClaims keeps the identities the catalogue has filed in Redis, so the operator
// import's repeat arrivals do not read the store again.
type FilingClaims struct{ client *redis.Client }

func NewFilingClaims(client *redis.Client) *FilingClaims { return &FilingClaims{client: client} }

func (c *FilingClaims) Held(ctx context.Context, identity string) (bool, error) {
	held, err := c.client.Exists(ctx, filingIdentityPrefix+identity).Result()
	if err != nil {
		return false, err
	}
	return held > 0, nil
}

func (c *FilingClaims) Hold(ctx context.Context, identity string, lifetime time.Duration) error {
	return c.client.Set(ctx, filingIdentityPrefix+identity, "filed", lifetime).Err()
}
