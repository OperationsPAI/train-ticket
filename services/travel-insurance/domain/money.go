package domain

import (
	"fmt"
	"strings"
)

type Money struct {
	Currency   string `json:"currency"`
	MinorUnits int64  `json:"minorUnits"`
}

func NewMoney(currency string, minorUnits int64) (Money, error) {
	m := Money{Currency: strings.ToUpper(strings.TrimSpace(currency)), MinorUnits: minorUnits}
	if err := m.ValidatePositive(); err != nil {
		return Money{}, err
	}
	return m, nil
}

func (m Money) ValidatePositive() error {
	if strings.TrimSpace(m.Currency) == "" {
		return fmt.Errorf("money currency is required")
	}
	if m.Currency != strings.ToUpper(m.Currency) {
		return fmt.Errorf("money currency must be upper-case ISO code")
	}
	if m.MinorUnits <= 0 {
		return fmt.Errorf("money minorUnits must be positive")
	}
	return nil
}

func (m Money) SameCurrency(other Money) bool { return m.Currency == other.Currency }
