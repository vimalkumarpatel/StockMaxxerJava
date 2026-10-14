# StockMaxxer (Java)

A Java port of StockMaxxerPython (`../StockMaxxerPython`). Several simulated portfolios, each funded
with a deposit, get a daily recommendation that you accept or ignore. An accepted recommendation fills
at the next open. The prices are **real** (Yahoo daily bars), replayed one trading day at a time: a
nightly pipeline of independent jobs moves the simulated day forward to the next real trading day.

The slice deliberately prefers simplicity to realism. Every simplification is listed
[below](#simplifications-and-why) with its reason, and marked `Simplification:` in the code.

## Run it

You need JDK 21+ (built and tested on JDK 26), Maven 3.9+, and Node 20+ for the UI. Run the `java`
commands from the repo root: the database is `data/stockmaxxer.sqlite`, relative to the working
directory (override it with `SM_DB_PATH`).

```bash
mvn -q package                                                      # build and run the tests (offline)
java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=seed   # download real prices; the day is 2026-01-02
java -jar stockmaxxer-app/target/stockmaxxer-app.jar                # API on http://localhost:8080/api/v1
cd stockmaxxer-ui && npm install && npm run dev                     # UI on http://localhost:3000
```

`seed` needs the network (about 10 seconds). After that, `scripts/nightly.sh` is offline: it replays the
stored history. Run `scripts/fetch.sh` to download the days that are new since then. Catching up from
2026-01-02 to today is about 200 nights; each night starts 6 JVMs, so it is slow but correct.

After a change to `schema.sql`, delete `data/stockmaxxer.sqlite*` and seed again: there are no migrations.

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

## Explanations

Every line of a recommendation, CASH included, carries a 2–3 sentence explanation for a reader who is
not a specialist. The UI shows it in the **Explanation** column. Each explanation says:

1. Why the stock is in or out of the top 8: its rank among the 12 stocks for price change over the
   last 20 trading days.
2. Why its part of the portfolio has that size: its price moves less or more than the other top
   stocks, it's at the 15% limit, or a *simulated* risk check trimmed it.
3. What the system does: buys, buys more, sells some, sells all, or holds.

For example: *"MU is number 5 of 12 stocks for price change in the last 20 trading days (+14.4%),
so it is in the top 8. Its price moves less each day than most of the other top stocks, so it gets a
larger part. The system buys more: its part changes from 13.72% to 13.75%."*

- **Writing rules:** explanations follow the [ASD-STE100](https://www.asd-ste100.org/) (Simplified
  Technical English) writing rules: at most 25 words in a sentence, active voice, one idea in a
  sentence, simple words. A test enforces the sentence count, the sentence length and no contractions.
  The STE dictionary of approved words isn't checked by a tool.
- **Where they come from:** they're built from fixed templates, using the same numbers as the
  recommendation itself. So they can't disagree with the trade, and they need no LLM or API key.

## Holdings, lots and cash

- **Every buy is a lot:** the day, the number of shares, and the price paid per share (that day's open).
  A holding is the sum of its lots, and its **cost basis** is what was paid for the shares still held.
- **Buying more at a different price** adds a new lot. Older lots stay as they were.
- **Trimming**, whether at a profit or a loss, sells at the next open and takes shares from the
  **oldest lot first (FIFO)**. A lot is used up whole or reduced; the price of what remains never
  changes. The proceeds go to cash.
- **Selling out:** when a name drops out of the recommendation, all its lots are sold at the next open
  and the proceeds go to cash.
- **Selling the whole portfolio** isn't possible yet. Both engines always stay mostly invested (cash is
  2% to 15%), and archiving a portfolio freezes it without selling anything.
- **Cash** is one number per portfolio, not a lot:
  - It starts as the deposit and changes only at fills, where it becomes whatever isn't invested at the
    open, including sale proceeds.
  - It's never negative, because shares round down.
  - It earns no interest.
- **Not recorded yet:** realised gains and losses. For now they show only as the change in cash.

## The nightly jobs

Each job runs on its own: `java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=<name>`.
- Jobs share nothing but the database, and re-running one is harmless.
- `scripts/nightly.sh` runs them in order; schedule it with cron for a real nightly pipeline.
- A failing job exits non-zero, which stops the script.

| Job | What it does |
|---|---|
| `seed` | One-off: downloads daily bars from 2025-01-02 into `history`, reveals them up to 2026-01-02 and sets the simulated day there |
| `fetch` | Downloads the bars that are new since the last fetch, up to yesterday (`scripts/fetch.sh`) |
| `advance` | Moves the simulated day to the next day SPY traded (real holidays are skipped) |
| `prices` | Reveals today: copies today's bars from `history` into `bars` |
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
| GET | `/portfolios/{id}` | With cash, NAV and holdings. Each holding has shares, market value (at the simulated day's close), cost basis (what was paid for the shares still held) and % of NAV |
| DELETE | `/portfolios/{id}` | archive (soft delete) |
| POST | `/portfolios/{id}/restore` | un-archive |
| POST | `/portfolios/{id}/recommendations` | generate today's recommendation now |
| GET | `/portfolios/{id}/decisions` | Recommendations, newest first, with their lines. Each line has its prior and target weight, the action, the (simulated) severity and a plain-language `explanation` |
| POST | `/portfolios/{id}/decisions/{decisionId}/accept` / `ignore` | |
| GET | `/portfolios/{id}/equity` | NAV per day next to the benchmark, rebased to the deposit |
| GET | `/benchmarks` | The benchmarks a portfolio can be compared against, i.e. the allowed values for `benchmark` when creating one (the UI's Benchmark dropdown). Example: `["SPY","QQQ","IWM","DIA"]` |
| GET | `/engines` | The recommendation engines you can choose, i.e. the allowed values for `engine`, each with a one-line description (the UI's Engine dropdown). Example: `[{"id":"quant_llm","description":"quant_only, then SIMULATED severity trims ..."},{"id":"quant_only","description":"Top 8 of 12 by 20-day return ..."}]` |
| GET | `/clock` | The current simulated trading day. Recommendations are made for this day and can only be accepted while it lasts; the `advance` job (run by `scripts/nightly.sh`) moves it to the next real trading day. Example: `{"today":"2026-02-18"}` |

Errors return `{"error": "..."}`:
- 400 for bad input
- 404 when something is not found
- 409 when an action isn't allowed now, such as accepting yesterday's recommendation or proposing
  twice in one day

## Simplifications and why

| Real-world design | Here | Why |
|---|---|---|
| Wall clock, a 09:30 ET cutoff, market hours | A **simulated trading day** stored in the database; the nightly run advances it through real history. A recommendation can be accepted only on its own day, and it fills at the next day's open | Same rule with no time zones. A demo day takes seconds, and tests need no fake clock |
| Exchange holiday calendar | The days SPY traded are the trading days | The real calendar comes with the data |
| Raw prices plus corporate actions, total returns | Yahoo's **split-adjusted** daily open and close, stored once and never overwritten; dividends ignored | Far less code. A split *after* the download would show as a price jump, and returns are price returns (for the benchmark too) |
| Fetching day D on day D | Day D is fetched on D+1 | During market hours Yahoo returns the unfinished session; storing it would freeze a wrong close |
| Universe screen (SIC codes, 10-K count, dollar volume) | Each benchmark's **12 largest holdings** (on 2026-10-13), one share class per company. A portfolio ranks its own benchmark's list | A rule, not a hand-picked list, with no screen to build. It has survivorship bias: these are today's largest names |
| σ₆₀, 12-month momentum, top 15 | Top 8 by 20-day return, weights ∝ 1/σ₂₀ (15% name cap kept) | Same shape; a month of history is enough |
| Sector cap, no-trade band | Not applied | 12 names per universe is too few for a sector cap, and every accepted recommendation simply rebalances to its target |
| Filing diff + LLM + quote verification | `quant_llm` = `quant_only` plus a **simulated** severity 0–5 per symbol and day. Trim = weight × severity × 10%; trimmed weight goes to cash, capped at 15% by scaling every trim pro-rata | Keeps the engine choice meaningful and exercises the trim-only and cash-cap rules. The real signal plugs in behind the same engine switch |
| Holdings snapshot per decision, audit records | Current holdings + cash; each decision keeps its prior and target weights | Enough to see what was proposed and what was applied |
| Job watermark tables | Re-runnable SQL: `INSERT OR IGNORE`, status filters, one decision per portfolio per day | Safe re-runs without bookkeeping |
| Spring Data JDBC repositories | Spring `JdbcClient` with plain SQL | Fewer lines than mapping composite keys and decimal converters |
| 11 Maven modules | 3: `core`, `app`, `jobs` | Still separates the web app from the jobs; more modules split out as subsystems arrive |
| Next.js + TypeScript + charts | Next.js, one plain-JSX page, tables | Fewest files; `/api` is proxied, so no CORS setup |
| Beta / alpha / IR against the benchmark | Equity table with the benchmark rebased to the deposit | Shows the chosen benchmark with no statistics code |
| A look-ahead guard | Decisions read only `bars`, which holds the days up to the simulated day; `history` is copied in one day at a time | Point-in-time holds by construction |
| `archived_at` timestamp | An `archived` flag | Nothing reads the time |
| LLM-written narratives (the original's one-sentence framing per position) | Fixed-template explanations (see [Explanations](#explanations)) | Every number comes from the decision itself, so an explanation can't disagree with the trade, and no API key is needed |
| Choosing which lot to sell, tax rules (short vs long term, wash sales) | **FIFO lots**: every sale takes the oldest lot first (see [Holdings, lots and cash](#holdings-lots-and-cash)) | FIFO is the common default and the simplest order to apply; HIFO or LIFO would only change which lot is sold first |

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
- the forecast range
- backtests and evals
- the audit trail

## Layout

```
stockmaxxer-core/    Market (simulated clock, history replay), Yahoo (prices), Engines, Portfolios (lifecycle), schema.sql
stockmaxxer-app/     Spring Boot JSON API
stockmaxxer-jobs/    the nightly jobs (no web server)
stockmaxxer-ui/      the Next.js page
scripts/nightly.sh   one simulated night (offline)
scripts/fetch.sh     download what is new (network)
```
