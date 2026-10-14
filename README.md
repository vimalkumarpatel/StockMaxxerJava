# StockMaxxer (Java)

A Java port of StockMaxxerPython (`../StockMaxxerPython`). This is currently its **minimum slice**:
several simulated portfolios, each funded with a seed deposit, get a daily recommendation that you
accept or ignore. An accepted recommendation fills at the next open. A nightly pipeline of
independent jobs moves the simulated market forward one day at a time.

The slice deliberately prefers simplicity to realism. Every simplification is listed
[below](#simplifications-and-why) with its reason, and marked `Simplification:` in the code.

## Run it

You need JDK 21+ (built and tested on JDK 26), Maven 3.9+, and Node 20+ for the UI. Run the `java`
commands from the repo root: the database is `data/stockmaxxer.sqlite`, relative to the working
directory (override it with `SM_DB_PATH`).

```bash
mvn -q package                                                      # build and run the tests
java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=seed   # a month of synthetic prices
java -jar stockmaxxer-app/target/stockmaxxer-app.jar                # API on http://localhost:8080/api/v1
cd stockmaxxer-ui && npm install && npm run dev                     # UI on http://localhost:3000
```

## A simulated day

1. **Create a portfolio:** a name, a deposit (default $1000), a benchmark (SPY by default, or QQQ,
   IWM, DIA) and an engine (`quant_llm` by default, or `quant_only`). It starts as 100% cash.
2. **Get a recommendation:** click *Generate recommendation*, or wait for the nightly run. It is
   `PROPOSED`, and nothing in the portfolio changes yet.
3. **Accept or ignore** the whole recommendation, on the day it was made.
4. **Run the night:** `scripts/nightly.sh`.
   - Accepted recommendations fill at the next open (`FILLED`).
   - Unaccepted ones become `EXPIRED`.
   - Every portfolio is marked at the close.
   - Fresh recommendations are proposed.

   The page doesn't poll, so reload it afterwards.

```
PROPOSED ──accept──► ACCEPTED ──next open (settle job)──► FILLED    holdings change only here
   ├──ignore──────────► IGNORED
   └──the day ends────► EXPIRED
```

## The nightly jobs

Each job runs on its own: `java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=<name>`.
- Jobs share nothing but the database, and re-running one is harmless.
- `scripts/nightly.sh` runs them in order; schedule it with cron for a real nightly pipeline.
- A failing job exits non-zero, which stops the script.

| Job | What it does |
|---|---|
| `seed` | One-off: 30 weekdays of synthetic prices from 2026-01-05; sets the simulated day |
| `advance` | Moves the simulated day to the next weekday |
| `prices` | Writes today's synthetic bars (open, close) for 12 stocks and 4 benchmarks |
| `expire` | Earlier days' unaccepted recommendations → `EXPIRED` |
| `settle` | Accepted recommendations → `FILLED` at today's open |
| `mark` | Values every active portfolio at today's close (the equity curve) |
| `propose` | Today's recommendation for every active portfolio that doesn't have one yet |

## API

Base path `/api/v1`. There is no auth (it's a local, personal app). Decimals are sent as JSON
strings so they stay exact.

| Method | Path | |
|---|---|---|
| POST | `/portfolios` | `{"name", "initialDeposit"?, "benchmark"?, "engine"?}` |
| GET | `/portfolios[?includeArchived=true]` | list |
| GET | `/portfolios/{id}` | With cash, NAV and holdings. Each holding has shares, market value (at the simulated day's close), average cost basis and % of NAV |
| DELETE | `/portfolios/{id}` | archive (soft delete) |
| POST | `/portfolios/{id}/restore` | un-archive |
| POST | `/portfolios/{id}/recommendations` | generate today's recommendation now |
| GET | `/portfolios/{id}/decisions` | recommendations, newest first, with their lines |
| POST | `/portfolios/{id}/decisions/{decisionId}/accept` / `ignore` | |
| GET | `/portfolios/{id}/equity` | NAV per day next to the benchmark, rebased to the deposit |
| GET | `/benchmarks` | The benchmarks a portfolio can be compared against, i.e. the allowed values for `benchmark` when creating one (the UI's Benchmark dropdown). Example: `["SPY","QQQ","IWM","DIA"]` |
| GET | `/engines` | The recommendation engines you can choose, i.e. the allowed values for `engine`, each with a one-line description (the UI's Engine dropdown). Example: `[{"id":"quant_llm","description":"quant_only, then SIMULATED severity trims ..."},{"id":"quant_only","description":"Top 8 of 12 by 20-day return ..."}]` |
| GET | `/clock` | The current simulated trading day. Recommendations are made for this day and can only be accepted while it lasts; the `advance` job (run by `scripts/nightly.sh`) moves it to the next weekday. Example: `{"today":"2026-02-18"}` |

Errors return `{"error": "..."}`:
- 400 for bad input
- 404 when something is not found
- 409 when an action isn't allowed now, such as accepting yesterday's recommendation or proposing
  twice in one day

## Simplifications and why

| Real-world design | Here | Why |
|---|---|---|
| Wall clock, a 09:30 ET cutoff, market hours | A **simulated trading day** stored in the database; the nightly run advances it. A recommendation can be accepted only on its own day, and it fills at the next day's open | Same rule with no time zones. A demo day takes seconds, and tests need no fake clock |
| Exchange holiday calendar | Weekdays are trading days | Holidays change nothing in the lifecycle |
| Yahoo and EDGAR data | Deterministic **synthetic prices** for 12 stocks and 4 benchmarks, seeded per symbol and date | No network or API key. With no splits or dividends, close-to-close is the total return |
| Universe screen (SIC codes, 10-K count, dollar volume) | A fixed list of 12 tickers | The screen needs filings and volume data |
| σ₆₀, 12-month momentum, top 15 | Top 8 by 20-day return, weights ∝ 1/σ₂₀ (15% name cap kept) | Same shape; a month of history is enough |
| Sector cap, no-trade band | Not applied | The synthetic universe has no sectors, and every accepted recommendation simply rebalances to its target |
| Filing diff + LLM + quote verification | `quant_llm` = `quant_only` plus a **simulated** severity 0–5 per symbol and day. Trim = weight × severity × 10%; trimmed weight goes to cash, capped at 15% by scaling every trim pro-rata | Keeps the engine choice meaningful and exercises the trim-only and cash-cap rules. The real signal plugs in behind the same engine switch |
| Holdings snapshot per decision, audit records | Current holdings + cash; each decision keeps its prior and target weights | Enough to see what was proposed and what was applied |
| Job watermark tables | Re-runnable SQL: `INSERT OR IGNORE`, status filters, one decision per portfolio per day | Safe re-runs without bookkeeping |
| Spring Data JDBC repositories | Spring `JdbcClient` with plain SQL | Fewer lines than mapping composite keys and decimal converters |
| 11 Maven modules | 3: `core`, `app`, `jobs` | Still separates the web app from the jobs; more modules split out as subsystems arrive |
| Next.js + TypeScript + charts | Next.js, one plain-JSX page, tables | Fewest files; `/api` is proxied, so no CORS setup |
| Beta / alpha / IR against the benchmark | Equity table with the benchmark rebased to the deposit | Shows the chosen benchmark with no statistics code |
| A look-ahead guard | Future bars don't exist until the `prices` job writes them | Point-in-time holds by construction |
| `archived_at` timestamp | An `archived` flag | Nothing reads the time |
| Tax lots (FIFO or specific-lot) | One **average-cost** basis per holding, recorded in `holdings.cost_basis` at every fill: a buy adds what it paid, a sale removes the sold fraction | One number per holding and no lot table; average cost is the standard method for funds and enough to compare value with what was paid |

**Kept from the original design:**
- `BigDecimal` everywhere, stored as TEXT because SQLite has no exact decimal type.
- Target weights including CASH sum to exactly 1, because cash is the residual.
- The LLM engine can only lower a weight, never raise it.
- A 15% per-name cap.
- No transaction costs.
- One recommendation per portfolio per day.
- Archive is a soft delete.

## Not built yet

These are covered by the long-term plan:
- the real signal: EDGAR filings, the Item 1A diff, the LLM with its replay cache, quote verification
- real market data
- the forecast range
- backtests and evals
- the audit trail

## Layout

```
stockmaxxer-core/    Market (simulated clock, synthetic prices), Engines, Portfolios (lifecycle), schema.sql
stockmaxxer-app/     Spring Boot JSON API
stockmaxxer-jobs/    the nightly jobs (no web server)
stockmaxxer-ui/      the Next.js page
scripts/nightly.sh   one simulated night
```
