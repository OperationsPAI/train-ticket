// Package config holds travel-insurance's own published settings.
//
// The product catalogue lives in insurance-products.properties beside this file
// rather than in the service's code, so a premium, a coverage limit or a sales
// version is a settings change and not a rebuild. The values are compiled into
// the binary with the rest of the package, which keeps a deployment from coming
// up with a catalogue it cannot read.
package config

import (
	_ "embed"
	"fmt"

	"github.com/trainticket/greenfield/platform/go-kit/settings"
	"github.com/trainticket/greenfield/services/travel-insurance/domain"
)

//go:embed insurance-products.properties
var published []byte

// Product is one catalogue entry a policy can be issued from.
type Product struct {
	ProductID     string
	Code          domain.ProductCode
	Version       string
	Premium       domain.Money
	CoverageLimit domain.Money
}

// Catalogue is the set of published products and the version they were
// published under.
type Catalogue struct {
	Version  string
	Products []Product
}

// entries fixes the catalogue's order and the code each settings prefix belongs
// to, so a product cannot be published under another product's code.
var entries = []struct {
	prefix string
	code   domain.ProductCode
}{
	{"delay", domain.ProductDelayInsurance},
	{"cancellation", domain.ProductCancellationInsurance},
	{"accident", domain.ProductAccidentInsurance},
	{"baggage", domain.ProductBaggageInsurance},
}

// Load reads the embedded catalogue.
func Load() (Catalogue, error) {
	document, err := settings.Parse(published)
	if err != nil {
		return Catalogue{}, err
	}
	version, err := document.Text("catalogue.version")
	if err != nil {
		return Catalogue{}, err
	}
	catalogue := Catalogue{Version: version}
	for _, entry := range entries {
		product, err := readProduct(document, entry.prefix, entry.code)
		if err != nil {
			return Catalogue{}, fmt.Errorf("catalogue product %s: %w", entry.code, err)
		}
		catalogue.Products = append(catalogue.Products, product)
	}
	return catalogue, nil
}

func readProduct(document settings.Document, prefix string, code domain.ProductCode) (Product, error) {
	product := Product{Code: code}
	var err error
	if product.ProductID, err = document.Text(prefix + ".productId"); err != nil {
		return Product{}, err
	}
	if product.Version, err = document.Text(prefix + ".version"); err != nil {
		return Product{}, err
	}
	declared, err := document.Text(prefix + ".code")
	if err != nil {
		return Product{}, err
	}
	if declared != string(code) {
		return Product{}, fmt.Errorf("settings publish %s under %s", declared, code)
	}
	if product.Premium, err = readMoney(document, prefix+".premium"); err != nil {
		return Product{}, err
	}
	if product.CoverageLimit, err = readMoney(document, prefix+".coverageLimit"); err != nil {
		return Product{}, err
	}
	return product, nil
}

func readMoney(document settings.Document, prefix string) (domain.Money, error) {
	currency, err := document.Text(prefix + ".currency")
	if err != nil {
		return domain.Money{}, err
	}
	minorUnits, err := document.Int(prefix + ".minorUnits")
	if err != nil {
		return domain.Money{}, err
	}
	return domain.NewMoney(currency, minorUnits)
}
