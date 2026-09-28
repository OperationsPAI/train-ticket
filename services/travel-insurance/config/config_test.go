package config

import (
	"testing"

	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

// The loader is the only reader of the settings file, so the file is exercised
// through Load rather than parsed again here: a test that re-reads the
// properties would pass while the loader failed.
func TestLoadPublishesEveryProductOnce(t *testing.T) {
	catalogue, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if catalogue.Version == "" {
		t.Fatal("the catalogue carries no version")
	}
	if len(catalogue.Products) != 4 {
		t.Fatalf("products = %d, want 4", len(catalogue.Products))
	}
	seen := map[domain.ProductCode]bool{}
	for _, product := range catalogue.Products {
		if seen[product.Code] {
			t.Fatalf("product %s published twice", product.Code)
		}
		seen[product.Code] = true
		if product.ProductID == "" || product.Version == "" {
			t.Fatalf("product %s has no id or version", product.Code)
		}
		if err := product.Premium.ValidatePositive(); err != nil {
			t.Fatalf("product %s premium: %v", product.Code, err)
		}
		if err := product.CoverageLimit.ValidatePositive(); err != nil {
			t.Fatalf("product %s coverage limit: %v", product.Code, err)
		}
	}
}
