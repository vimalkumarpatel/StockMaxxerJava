-- Decimals are TEXT on purpose: SQLite has no exact decimal type, and NUMERIC affinity would turn
-- '1000.00' into a float. All arithmetic on them happens in Java with BigDecimal.
-- Dates are ISO text (yyyy-mm-dd), which sorts and compares chronologically.

-- Simplification: a simulated trading day instead of a wall clock with market hours and time zones.
CREATE TABLE IF NOT EXISTS sim_clock (
  id    INTEGER PRIMARY KEY CHECK (id = 1),
  today TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bars (
  symbol     TEXT NOT NULL,
  trade_date TEXT NOT NULL,
  open       TEXT NOT NULL,
  close      TEXT NOT NULL,
  PRIMARY KEY (symbol, trade_date)
);

CREATE TABLE IF NOT EXISTS portfolios (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT NOT NULL,
  initial_deposit TEXT NOT NULL,
  cash            TEXT NOT NULL,
  benchmark       TEXT NOT NULL,
  engine          TEXT NOT NULL,
  created_on      TEXT NOT NULL,
  archived        INTEGER NOT NULL DEFAULT 0
);

-- Simplification: the current holdings only, not a snapshot per decision.
-- cost_basis is what was paid for the shares (average cost), recorded at every fill.
CREATE TABLE IF NOT EXISTS holdings (
  portfolio_id INTEGER NOT NULL REFERENCES portfolios (id),
  symbol       TEXT NOT NULL,
  shares       TEXT NOT NULL,
  cost_basis   TEXT NOT NULL,
  PRIMARY KEY (portfolio_id, symbol)
);

-- One recommendation per portfolio per day. status: PROPOSED, ACCEPTED, FILLED, IGNORED, EXPIRED.
CREATE TABLE IF NOT EXISTS decisions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  portfolio_id INTEGER NOT NULL REFERENCES portfolios (id),
  as_of        TEXT NOT NULL,
  engine       TEXT NOT NULL,
  status       TEXT NOT NULL,
  nav          TEXT NOT NULL,
  fill_date    TEXT,
  UNIQUE (portfolio_id, as_of)
);

-- Includes a CASH line, so the target weights of one decision sum to exactly 1.
CREATE TABLE IF NOT EXISTS decision_lines (
  decision_id   INTEGER NOT NULL REFERENCES decisions (id),
  symbol        TEXT NOT NULL,
  prior_weight  TEXT NOT NULL,
  target_weight TEXT NOT NULL,
  severity      INTEGER NOT NULL,
  action        TEXT NOT NULL,
  PRIMARY KEY (decision_id, symbol)
);

CREATE TABLE IF NOT EXISTS equity (
  portfolio_id INTEGER NOT NULL REFERENCES portfolios (id),
  trade_date   TEXT NOT NULL,
  nav          TEXT NOT NULL,
  PRIMARY KEY (portfolio_id, trade_date)
);
