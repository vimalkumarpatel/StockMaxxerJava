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
export SM_SEC_USER_AGENT="StockMaxxer you@example.com" SM_ANTHROPIC_API_KEY=...
scripts/fetch.sh                                                    # filings, then Claude reads their new risks
java -jar stockmaxxer-app/target/stockmaxxer-app.jar                # API on http://localhost:8080/api/v1
cd stockmaxxer-ui && npm install && npm run dev                     # UI on http://localhost:3000
```

`seed` needs the network (about 10 seconds). After that, `scripts/nightly.sh` is offline: it replays the
stored history. Run `scripts/fetch.sh` to download the days that are new since then. Catching up from
2026-01-02 to today is about 200 nights; each night starts 6 JVMs, so it is slow but correct.

After a change to `schema.sql` or to how filings are read, delete `data/stockmaxxer.sqlite*`, then seed again and
run `scripts/fetch.sh`. There are no migrations. The raw filings and Claude's answers are kept in `data/`, so
nothing is downloaded or asked twice.

## Demo to a team

The demo replays real trading days one at a time. The audience sees each day arrive, a recommendation made
from that day's data, the trades filled at the next open, and the portfolios change. It runs **offline**:
prices, filings and Claude's answers all come from what `scripts/fetch.sh` stored, and `SM_LLM_REPLAY=1` makes
any missing answer an error instead of an API call. There are no keys or network calls in the meeting.

**1. Prepare, once, before the meeting** (needs the network and both keys):

```bash
mvn -q package
java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=seed   # real prices; the day is 2026-01-02
scripts/fetch.sh                                                    # filings, then Claude (about 170 calls, all cached)
java -jar stockmaxxer-app/target/stockmaxxer-app.jar                # in a second terminal
cd stockmaxxer-ui && npm run dev                                    # in a third; open http://localhost:3000
```

In the UI, create the portfolios the story needs:
- Two on the **same benchmark** with different engines tell the A/B story; for example, "QQQ with Claude"
  (`quant_llm`) and "QQQ quant only" (`quant_only`).
- A third on another benchmark, such as DIA.

Accept their first recommendations, then move to just before the filing season. Most 10-Ks arrive from late
January to the end of February, so a demo that starts there shows new filings changing the trims:

```bash
for i in $(seq 1 15); do scripts/nightly.sh; done          # 2026-01-02 → about 2026-01-26
```

Then stop the app and keep a copy of the database to reset to. SQLite keeps recent writes in `-wal` and `-shm`
files next to the database, so copy only while nothing has it open:

```bash
cp data/stockmaxxer.sqlite data/demo.sqlite
```

**2. Run it:**

```bash
# with the app stopped: back to the prepared start
rm -f data/stockmaxxer.sqlite-wal data/stockmaxxer.sqlite-shm
cp data/demo.sqlite data/stockmaxxer.sqlite
java -jar stockmaxxer-app/target/stockmaxxer-app.jar &
scripts/demo.sh 10 5                             # 10 trading days, 5 seconds apart
```

Put the terminal and the browser side by side. The page reloads every 5 seconds. Each day, `demo.sh` runs the
night's jobs:
- `advance`, `prices`: the next real day
- `expire`, `settle`, `mark`: yesterday's recommendations fill at today's open
- `propose`: today's recommendations
- `accept`: stands in for a person clicking *Accept*, with the same rules and audit rows
- `report`: prints the day.

```
======== 2026-02-02 ========
new filing: AAPL 10-Q filed 2026-01-30: severity 0
new filing: V 10-Q filed 2026-01-30: severity 0

QQQ with Claude (quant_llm, QQQ)
  filled at today's open: SELL 0.051523 AMD @ 212.1300; ... BUY 1.308971 WMT @ 114.1900;
  NAV $998.12, cash 6.50% | QQQ from the same start: $1017.78
  today's recommendation (ACCEPTED):
    NVDA   BUY    6.77% ->   8.14%  severity 4: "On September 15, 2025, China's antitrust regulators ..."
  past 63-day range at today's holdings: $934.10 to $1236.81 (median $1078.78), historical dispersion, not a prediction
```

**3. What to point at:**

- **Point-in-time:** "Day D's prices are revealed only now; nothing from D+1 exists yet."
- **Fetching:** "A 10-K filed yesterday counts from today." The report's `new filing:` lines show each
  filing on the day it starts to count.
- **The signal:** "NVDA's 10-Q adds a level-4 risk. Here is the exact sentence, checked word for word against the
  stored filing." It shows under the line's explanation in the UI, and in the report.
- **Trim-only:** "Claude can only make a part smaller. The trimmed part becomes cash, and cash stops at 15%."
  Compare the cash of the two QQQ portfolios.
- **The lifecycle:** "Accepted yesterday, filled at today's open." The holdings table shows the new lots and
  their cost basis.
- **A/B:** the two QQQ portfolios drift apart on the NAV chart, next to the benchmark.
- **The forecast:** "This range is how the same holdings moved in the past. It is not a prediction."
- **The audit trail:** finish with one recommendation's trail:
  `curl localhost:8080/api/v1/portfolios/1/decisions/1/audit`.
- **The whole window:**
  - `java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=backtest --benchmark=QQQ --from=2026-01-02`
  - `java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=evals`

**4. Manual mode:** to show the human decision, don't run `demo.sh`. Click *Accept* or *Ignore* in the UI,
then run `scripts/nightly.sh` once for each day.

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
   stocks, it's at the 15% limit, or its latest 10-K or 10-Q adds a new risk (see [The filing signal](#the-filing-signal)).
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

## The filing signal

`quant_llm` trims a stock when its latest 10-K or 10-Q adds a new risk. Claude reads only the new text and
names the risks; the code decides the weights.

1. **Filings** (`filings` job): every 10-K and 10-Q since 2024-01-01 of the 35 stocks, from SEC EDGAR. Each one is
   stored **complete**, so later signals and checks can use any part of it:
   - the raw document, gzipped, in `data/filings/{accession}.htm.gz` (it survives a database reset and is never
     downloaded twice)
   - `filings.text`: the whole document as normalized plain text
   - `filings.item1a`: the Risk Factors section, cut from `text`.

   That is about 380 filings: 75 MB of raw files and 125 MB of text in SQLite.
2. **The diff** (`signals` job): the Item 1A sentences that are new since the company's previous filing of the
   same form. A sentence is new when fewer than half of its 5-word runs appear in the old section.
3. **Claude** (`claude-sonnet-5-5`; set `SM_LLM_MODEL` to change it) gets only the new sentences and returns
   up to 10 risks. Each has a type from a closed list, a severity 0–5 on a fixed scale, a scope (firm or
   industry) and the sentence it came from. If nothing is new, Claude is not asked.
4. **The replay cache:** every answer is saved as `data/llm-cache/<sha256>.json`. The key covers the model, the
   prompt version and the exact prompt. A cached answer is always reused, so a second run makes no API calls.
   With `SM_LLM_REPLAY=1`, a cache miss is an error instead of a call. A cut-off or refused answer is never
   cached: it would replay forever as "no risks".
5. **Quote verification:** each quote must be an exact substring of the stored Item 1A, after the same
   normalizing. Both texts go through the same function, so the check and the UI use the same text. A
   paraphrased or invented quote is kept, but marked `verified = 0`, and it can never move a weight. The
   `signals` job prints how many quotes passed and failed. On NVDA's 11 filings, 80 of 80 passed.
6. **The trim:** on each day, a stock's severity comes from its latest scored filing filed **before** that day.
   It is the highest severity among that filing's **verified** firm-scope events. The UI shows the quote under
   the line's explanation.
7. **No Item 1A, no recommendation:** a stock can be recommended only if its latest 10-K filed before that day
   has an Item 1A section. This applies to both engines, so the comparison stays fair. Otherwise its risks
   can't be checked, so the stock is left out and a held position is sold. Its line says why, and the
   proposal's audit row lists it under `noRiskFactors`. Today this leaves out INTC: its filings have only the
   contents line "Item 1A. Risk Factors Pages 37-51". Trim = weight × severity × 10%.
   Trimmed weight goes to cash, and cash is capped at 15% by scaling every trim down pro-rata.

| Severity | Meaning |
|---|---|
| 0 | No change, or industry-wide boilerplate only |
| 1 | Firm-specific rewording, no new substantive risk |
| 2 | New risk added, generic, no named exposure |
| 3 | New firm-specific risk with a **named** exposure (a customer, supplier, country or regulator) |
| 4 | New firm-specific risk that is **quantified or material** |
| 5 | Existential: going concern, material weakness, restatement, auditor departure, litigation that threatens operations |

**Settings:**
- `SM_SEC_USER_AGENT`: a contact, for example `"StockMaxxer you@example.com"`. SEC requires it.
- `SM_ANTHROPIC_API_KEY`, or `ANTHROPIC_API_KEY`: needed only for answers that are not cached yet.

The web app and `scripts/nightly.sh` need neither.

## Backtests and evals

Both are read-only jobs over the revealed days. They use the same engines, signals and replay cache as the
live run, so they make no API calls once `scripts/fetch.sh` has run.

**Backtest** (`--job=backtest --benchmark=QQQ --from=2025-03-03`): each day, each engine's target is filled at
the next open and marked at that day's close, as if every recommendation were accepted. Fills follow the
settle job's rule: fractional shares rounded down, no costs. Next to the engines is the benchmark, bought at
the first fill and held. It prints the end NAV, return, largest fall from a peak, and average cash.

```
QQQ universe, 2025-03-03 to 2026-01-05
                            end NAV    return  max drawdown  avg cash
quant_only                  1601.31   +60.13%        21.69%     2.00%
quant_llm                   1585.55   +58.56%        20.81%     5.77%
QQQ (buy and hold)          1250.54   +25.05%        17.12%     0.00%
```

(Only NVDA's filings were scored for that run. Treat it as a format example, not a result.)

**Evals** (`--job=evals`): does a filing's severity say anything about what the stock does next?
- **One row per filing, never per event.** A filing's ~10 events share one forward return, so counting it 10
  times would invent a pattern.
- Each row is the filing's severity (its highest verified firm-scope event, or 0) and the stock's return over
  the next 20 trading days minus the average of all 35 stocks. The return runs from the open of the first
  trading day after the filing.
- Filings without a full window are left out.
- It also prints the quote verification rate and the share of industry-scope events (dropped).
- With a few hundred filings, the numbers are **underpowered: no alpha claim**.

## The audit trail

Everything that happens to a recommendation adds one row to the `audit` table, in the same transaction as the
change:

| kind | detail (JSON) |
|---|---|
| `PROPOSED` | Engine, benchmark, universe, prompt version, a sha256 of every close the engine could read, NAV, prior and target weights including CASH, trim scale, the names at the 15% cap, each trim's filing and verified quote, its replay-cache key and the model that answered, and the dropped quotes in each stock's latest filing |
| `ACCEPTED`, `IGNORED`, `EXPIRED` | Nothing more: the row itself records when |
| `FILLED` | Each trade at the open (symbol, BUY or SELL, shares, price) and the cash after |

The trail is **append-only**: two SQLite triggers reject any UPDATE or DELETE on `audit`. A test replays a
proposal: it computes the target again from the same point-in-time inputs and gets the recorded weights.

## The nightly jobs

Each job runs on its own: `java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=<name>`.
- Jobs share nothing but the database, and re-running one is harmless.
- `scripts/nightly.sh` runs them in order; schedule it with cron for a real nightly pipeline.
- A failing job exits non-zero, which stops the script.

| Job | What it does |
|---|---|
| `seed` | One-off: downloads daily bars from 2025-01-02 into `history`, reveals them up to 2026-01-02 and sets the simulated day there |
| `fetch` | Downloads the bars that are new since the last fetch, up to yesterday (`scripts/fetch.sh`) |
| `filings` | Downloads and stores the 10-K and 10-Q filings not stored yet (`scripts/fetch.sh`) |
| `backtest` | `--benchmark=SPY --from=2026-01-02`: both engines and buy-and-hold of the benchmark over the revealed days (see below) |
| `evals` | Severity against the next 20 trading days' excess return, one row per filing (see below) |
| `signals` | Asks Claude about the new risk factors of every filing not scored yet, through the replay cache (`scripts/fetch.sh`) |
| `advance` | Moves the simulated day to the next day SPY traded (real holidays are skipped) and reveals its bars, in one transaction so the UI never sees a day without bars |
| `prices` | Reveals today: copies today's bars from `history` into `bars` (already done by `advance`; a safe re-run) |
| `expire` | Earlier days' unaccepted recommendations → `EXPIRED` |
| `settle` | Accepted recommendations → `FILLED` at today's open |
| `mark` | Values every active portfolio at today's close (the equity curve) |
| `propose` | Today's recommendation for every active portfolio that doesn't have one yet |
| `accept` | Demo only: accepts today's recommendation of every active portfolio, as the UI's Accept button does |
| `report` | Demo only: prints today's fills, NAV against the benchmark, recommendation with quotes, and forecast range |

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
| GET | `/portfolios/{id}/decisions` | Recommendations, newest first, with their lines. Each line has its prior and target weight, the action, the severity from the stock's latest filing, a plain-language `explanation`, and `quote`: the verified filing sentence behind a trim |
| POST | `/portfolios/{id}/decisions/{decisionId}/accept` / `ignore` | |
| GET | `/portfolios/{id}/decisions/{decisionId}/audit` | The recommendation's audit trail, oldest first (see [The audit trail](#the-audit-trail)) |
| GET | `/portfolios/{id}/equity` | NAV per day next to the benchmark, rebased to the deposit |
| GET | `/portfolios/{id}/forecast` | The forecast range: `{horizonDays: 63, p10, p50, p90, note}`. A 5-day block bootstrap (10,000 paths, fixed seed 42) of the holdings' daily returns over the last 252 revealed days, at today's weights; cash returns 0. It is **historical dispersion, not a prediction**, and the UI says so |
| GET | `/benchmarks` | The benchmarks a portfolio can be compared against, i.e. the allowed values for `benchmark` when creating one (the UI's Benchmark dropdown). Example: `["SPY","QQQ","IWM","DIA"]` |
| GET | `/engines` | The recommendation engines you can choose, i.e. the allowed values for `engine`, each with a one-line description (the UI's Engine dropdown). Example: `[{"id":"quant_llm","description":"quant_only, then trims for new risks in each stock's latest 10-K or 10-Q ..."},{"id":"quant_only","description":"Top 8 of the benchmark's 12 largest holdings by 20-day return ..."}]` |
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
| Item 1A extractor | The longest span from an "Item 1A. Risk Factors" heading to the next "Item 1B/1C/2" (10-K) or "Item 2" (10-Q). A span under 10 words is a contents line, not a section | A few lines. The shortest real section seen ("no material changes") is about 20 words |
| Which stocks need an Item 1A | Only the latest **10-K** must have one, so a 10-Q without one (GS) doesn't remove the stock | Many 10-Qs only refer back to the 10-K's risk factors |
| Filing diff against the governing filing | Each filing against the previous filing of the **same form**; the first one of each form is a baseline with no events | No form-mixing rules. Filings are fetched from 2024-01-01, so every filing in the replay has a predecessor |
| Acceptance time from the SGML header | EDGAR's filing date; a filing counts from the **next** day | Never too early, and no time zones |
| Severity aggregation, absorbing severity 5, co-occurrence backstop | A stock's severity is the **highest firm-scope** severity in its **latest scored** filing; events the model calls industry-wide are dropped. No "5 stays until superseded", no time decay, no clustering of the same event across names | Short and explainable. As in the Python version, the max over ~10 events per filing is usually 4, so most held names are trimmed and the cash cap binds |
| LLM refusal fallbacks | A refused or cut-off answer is not cached; that filing fails, the others go on, and the next run tries it again | No beta features. The job's error lists the filing |
| Holdings snapshot per decision | Current lots + cash; the audit trail keeps each decision's prior and target weights and each fill's trades | Enough to see what was proposed and what was applied |
| Raw prompt and response in the audit | Their replay-cache key and the model that answered; the answer itself is in `data/llm-cache/` | Keeps the database small |
| An audit hash chain | Append-only by SQLite triggers | Tamper-evidence would answer a question nobody asked here |
| Job watermark tables | Re-runnable SQL: `INSERT OR IGNORE`, status filters, one decision per portfolio per day | Safe re-runs without bookkeeping |
| Spring Data JDBC repositories | Spring `JdbcClient` with plain SQL | Fewer lines than mapping composite keys and decimal converters |
| 11 Maven modules | 3: `core`, `app`, `jobs` | Still separates the web app from the jobs; more modules split out as subsystems arrive |
| Next.js + TypeScript + charts | Next.js, one plain-JSX page, tables | Fewest files; `/api` is proxied, so no CORS setup |
| Forecast in exact decimals | The bootstrap uses doubles, rounded to cents at the end; weights stay fixed over the 63 days | It is a statistic, not money |
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

From the long-term plan, still missing:
- a universe screen (SIC codes, 10-K count, dollar volume) instead of the fixed lists
- raw prices with corporate actions, and total returns
- the co-occurrence backstop and a severity aggregation that does not saturate at 4
- benchmark statistics (beta, alpha, information ratio)

## Layout

```
stockmaxxer-core/    Market (simulated clock, history replay), Yahoo (prices), Edgar + Filings (10-K/10-Q text),
                     Llm + Prompts (Claude, replay cache), Signals (severity), Engines, Portfolios (lifecycle), schema.sql
stockmaxxer-app/     Spring Boot JSON API
stockmaxxer-jobs/    the nightly jobs (no web server)
stockmaxxer-ui/      the Next.js page
scripts/nightly.sh   one simulated night (offline)
scripts/fetch.sh     download what is new (network)
scripts/demo.sh      a day-by-day team demo (offline)
scripts/stop.sh      frees ports 8080 and 3000: stops the API and UI cleanly, kills them after 15 s
```
