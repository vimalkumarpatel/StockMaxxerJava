-- Decimals are TEXT on purpose: SQLite has no exact decimal type, and NUMERIC affinity would turn
-- '1000.00' into a float. All arithmetic on them happens in Java with BigDecimal.
-- Dates are ISO text (yyyy-mm-dd), which sorts and compares chronologically.

-- Simplification: a simulated trading day instead of a wall clock with market hours and time zones.
CREATE TABLE IF NOT EXISTS sim_clock (
  id    INTEGER PRIMARY KEY CHECK (id = 1),
  today TEXT NOT NULL
);

-- Real daily bars as downloaded (Yahoo). Decisions never read this table: the prices job copies one day at a
-- time into bars, so bars holds only the days up to the simulated day.
CREATE TABLE IF NOT EXISTS history (
  symbol     TEXT NOT NULL,
  trade_date TEXT NOT NULL,
  open       TEXT NOT NULL,
  close      TEXT NOT NULL,
  PRIMARY KEY (symbol, trade_date)
);

-- The days revealed so far.
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

-- One row per purchase (a lot): the day it was bought, the price paid per share, and how many of those
-- shares are still held. A holding is the sum of its lots. Sales take shares from the oldest lots first
-- (FIFO), so a lot's price never changes. Simplification: the current lots only, not a snapshot per decision.
CREATE TABLE IF NOT EXISTS lots (
  portfolio_id INTEGER NOT NULL REFERENCES portfolios (id),
  symbol       TEXT NOT NULL,
  acquired_on  TEXT NOT NULL,
  shares       TEXT NOT NULL,
  price        TEXT NOT NULL,
  PRIMARY KEY (portfolio_id, symbol, acquired_on)
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
-- explanation is 2-3 plain-language sentences about the line (ASD-STE100 writing rules).
CREATE TABLE IF NOT EXISTS decision_lines (
  decision_id   INTEGER NOT NULL REFERENCES decisions (id),
  symbol        TEXT NOT NULL,
  prior_weight  TEXT NOT NULL,
  target_weight TEXT NOT NULL,
  severity      INTEGER NOT NULL,
  action        TEXT NOT NULL,
  explanation   TEXT NOT NULL,
  quote         TEXT,           -- the verified filing sentence behind a trim, NULL if not trimmed
  PRIMARY KEY (decision_id, symbol)
);

CREATE TABLE IF NOT EXISTS equity (
  portfolio_id INTEGER NOT NULL REFERENCES portfolios (id),
  trade_date   TEXT NOT NULL,
  nav          TEXT NOT NULL,
  PRIMARY KEY (portfolio_id, trade_date)
);

-- One 10-K or 10-Q, stored complete: text is the whole document as normalized plain text (later signals can
-- use any part of it), and item1a is the Risk Factors section cut from it. The raw document is kept in
-- data/filings/{accession}.htm.gz. scored = 1 once the signals job has read the filing.
CREATE TABLE IF NOT EXISTS filings (
  accession TEXT PRIMARY KEY,
  symbol    TEXT NOT NULL,
  cik       INTEGER NOT NULL,
  form      TEXT NOT NULL,
  filed_on  TEXT NOT NULL,
  doc_url   TEXT NOT NULL,
  text      TEXT NOT NULL,
  item1a    TEXT NOT NULL,
  scored    INTEGER NOT NULL DEFAULT 0,
  -- Set by the signals job: how many Item 1A sentences were new, and the replay-cache key and the model
  -- that actually answered (NULL when nothing was new, so Claude was not asked).
  new_sentences INTEGER,
  llm_key       TEXT,
  llm_model     TEXT
);
CREATE INDEX IF NOT EXISTS filings_by_symbol ON filings (symbol, form, filed_on);

-- The risks Claude found in one filing's new Item 1A sentences. scope is 'firm' or 'industry'. quote is the
-- sentence Claude says it read the risk in; verified = 1 when it is an exact substring of the stored Item 1A.
-- Only verified firm-scope events can trim a weight.
CREATE TABLE IF NOT EXISTS events (
  accession TEXT NOT NULL REFERENCES filings (accession),
  n         INTEGER NOT NULL,
  type      TEXT NOT NULL,
  severity  INTEGER NOT NULL,
  scope     TEXT NOT NULL,
  quote     TEXT NOT NULL,
  verified  INTEGER NOT NULL,
  PRIMARY KEY (accession, n)
);

-- The audit trail: one row per thing that happened to a recommendation (PROPOSED, ACCEPTED, IGNORED, EXPIRED,
-- FILLED), with a JSON detail. Append-only: triggers created by the Audit class reject UPDATE and DELETE.
CREATE TABLE IF NOT EXISTS audit (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  decision_id INTEGER NOT NULL REFERENCES decisions (id),
  day         TEXT NOT NULL,
  kind        TEXT NOT NULL,
  detail      TEXT NOT NULL
);
