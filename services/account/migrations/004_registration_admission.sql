-- The identity API admits new accounts on a per-client budget, and the count
-- lives here rather than in the process: a replica that counted its own window
-- would admit one budget per replica, while the budget is the platform's
-- statement about the identity endpoint and has to hold across them.
--
-- One row per client key: the window that row was last counted in and how many
-- new accounts the client has asked for inside it. A row whose window has passed
-- is reset by the client's next request, so the table is bounded by the clients
-- the platform has seen rather than by time and needs no sweep. A million-client
-- day is a million rows of three columns; see
-- services/account/src/adapters/storage/registration-admission.ts for the
-- counter itself.
CREATE TABLE IF NOT EXISTS registration_admissions (
  client_key   text PRIMARY KEY,
  window_start timestamptz NOT NULL,
  attempts     integer NOT NULL DEFAULT 0,
  updated_at   timestamptz NOT NULL DEFAULT now()
);
