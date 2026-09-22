// Package boarding holds the register of readers an estate admits a boarding
// fact from.
//
// The register is the estate's own data. The readers an estate operates are
// staged in its database by the deployment that runs it, rather than carried in
// the branch that measures a boarding fact against them, so a lane can be
// opened or closed without a service release. A deployment that states no reader
// is answered with the vocabulary the readers this image was built for report,
// which is what a boarding fact was measured against before the register
// existed.
package boarding

import (
	"context"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
)

// RegisterTable is the estate's register of boarding readers. A deployment
// states a reader by a row in it; the service only reads it.
const RegisterTable = "boarding_reader_register"

// RegisterQuery reads the readers the estate states.
const RegisterQuery = `SELECT reader FROM ` + RegisterTable

// vocabulary is what the readers this image was built for report. It answers a
// deployment that states no reader of its own.
var vocabulary = []string{"GATE", "STATION", "PROVIDER", "CONDUCTOR", "ADMIN"}

// Register is the set of readers a boarding fact may arrive under.
type Register struct {
	readers map[string]struct{}
}

// NewRegister returns the register the named readers state, in the order they
// are named. A reader is admitted whatever case or surrounding space the fact
// that names it carries.
func NewRegister(readers ...string) *Register {
	register := &Register{readers: make(map[string]struct{}, len(readers))}
	for _, reader := range readers {
		register.admit(reader)
	}
	return register
}

// Vocabulary returns the register the readers this image was built for state.
func Vocabulary() *Register {
	return NewRegister(vocabulary...)
}

// Admits reports whether a boarding fact a reader reported under reader is one
// the register admits.
func (r *Register) Admits(reader string) bool {
	if r == nil {
		return false
	}
	_, admitted := r.readers[normalize(reader)]
	return admitted
}

func (r *Register) admit(reader string) {
	reader = normalize(reader)
	if reader == "" {
		return
	}
	r.readers[reader] = struct{}{}
}

// held is the register the estate stated when this process started.
var held *Register

// Load reads the estate's register, once, at startup, and holds it for the life
// of the process: a row staged beneath a running process is read by the process
// that starts after it. A register that states no reader is answered with the
// vocabulary this image was built for.
func Load(ctx context.Context, pool *pgxpool.Pool) error {
	readers, err := readRegister(ctx, pool)
	if err != nil {
		return err
	}
	if len(readers) == 0 {
		held = Vocabulary()
		return nil
	}
	held = NewRegister(readers...)
	return nil
}

// Admits reports whether a boarding fact may arrive under reader.
func Admits(reader string) bool {
	if held == nil {
		return Vocabulary().Admits(reader)
	}
	return held.Admits(reader)
}

func readRegister(ctx context.Context, pool *pgxpool.Pool) ([]string, error) {
	if pool == nil {
		return nil, fmt.Errorf("boarding register: no database pool")
	}
	rows, err := pool.Query(ctx, RegisterQuery)
	if err != nil {
		return nil, fmt.Errorf("boarding register: %w", err)
	}
	defer rows.Close()
	var readers []string
	for rows.Next() {
		var reader string
		if err := rows.Scan(&reader); err != nil {
			return nil, fmt.Errorf("boarding register: %w", err)
		}
		readers = append(readers, reader)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("boarding register: %w", err)
	}
	return readers, nil
}

func normalize(reader string) string {
	return strings.ToUpper(strings.TrimSpace(reader))
}
