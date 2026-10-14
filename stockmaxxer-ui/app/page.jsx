'use client';

// Simplification: one plain-JSX page with tables (no TypeScript, no charts, no component library).
// It does not poll: reload it after running scripts/nightly.sh.

import { useCallback, useEffect, useState } from 'react';

// Weights and money arrive as exact decimal strings; they become numbers only for display.
const money = (v) => (v == null ? '' : Number(v).toLocaleString('en-US', { style: 'currency', currency: 'USD' }));
const pct = (v) => (v == null ? '' : `${(Number(v) * 100).toFixed(2)}%`);

const STATUS_HELP = {
  PROPOSED: 'waiting for you: accept or ignore it before the next simulated day',
  ACCEPTED: 'pending: fills at the next open (after the nightly run)',
  FILLED: 'applied to the portfolio',
  IGNORED: 'ignored: the portfolio was left unchanged',
  EXPIRED: 'expired: not accepted in time, the portfolio was left unchanged',
};

async function api(path, options = {}) {
  const res = await fetch(`/api/v1${path}`, { headers: { 'Content-Type': 'application/json' }, ...options });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error ?? `${res.status} ${res.statusText}`);
  return body;
}

export default function Home() {
  const [today, setToday] = useState('');
  const [benchmarks, setBenchmarks] = useState([]);
  const [engines, setEngines] = useState([]);
  const [portfolios, setPortfolios] = useState([]);
  const [navs, setNavs] = useState({});
  const [showArchived, setShowArchived] = useState(false);
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [decisions, setDecisions] = useState([]);
  const [equity, setEquity] = useState([]);
  const [forecast, setForecast] = useState(null);
  const [form, setForm] = useState({ name: '', initialDeposit: '1000.00', benchmark: 'SPY', engine: 'quant_llm' });
  const [error, setError] = useState('');

  // Runs an action and shows its error message instead of failing silently.
  const guarded = (fn) => async (...args) => {
    setError('');
    try {
      await fn(...args);
    } catch (e) {
      setError(e.message);
    }
  };

  const loadList = useCallback(async () => {
    const [clock, list] = await Promise.all([api('/clock'), api(`/portfolios?includeArchived=${showArchived}`)]);
    // Simplification: one detail call per portfolio for its NAV, instead of adding NAV to the list endpoint.
    const details = await Promise.all(list.map((p) => api(`/portfolios/${p.id}`)));
    setToday(clock.today);
    setPortfolios(list);
    setNavs(Object.fromEntries(details.map((d) => [d.portfolio.id, d.nav])));
  }, [showArchived]);

  const loadPortfolio = useCallback(async (id) => {
    if (id == null) {
      setDetail(null);
      setDecisions([]);
      setEquity([]);
      setForecast(null);
      return;
    }
    const [d, ds, eq, fc] = await Promise.all([
      api(`/portfolios/${id}`),
      api(`/portfolios/${id}/decisions`),
      api(`/portfolios/${id}/equity`),
      api(`/portfolios/${id}/forecast`),
    ]);
    setDetail(d);
    setDecisions(ds);
    setEquity(eq);
    setForecast(fc);
  }, []);

  useEffect(() => {
    guarded(async () => {
      const [b, e] = await Promise.all([api('/benchmarks'), api('/engines')]);
      setBenchmarks(b);
      setEngines(e);
    })();
  }, []);
  useEffect(() => {
    guarded(loadList)();
  }, [loadList]);
  useEffect(() => {
    guarded(loadPortfolio)(selected);
  }, [selected, loadPortfolio]);

  const refresh = async () => {
    await loadList();
    await loadPortfolio(selected);
  };

  // Reload every 5 seconds, so the page follows scripts/demo.sh (or the nightly jobs) without a manual reload.
  useEffect(() => {
    const timer = setInterval(() => guarded(refresh)(), 5000);
    return () => clearInterval(timer);
  });

  const create = guarded(async (event) => {
    event.preventDefault();
    const p = await api('/portfolios', { method: 'POST', body: JSON.stringify(form) });
    setForm({ ...form, name: '' });
    await loadList();
    setSelected(p.id);
  });
  const generate = guarded(async () => {
    await api(`/portfolios/${selected}/recommendations`, { method: 'POST' });
    await refresh();
  });
  const decide = guarded(async (decisionId, verb) => {
    await api(`/portfolios/${selected}/decisions/${decisionId}/${verb}`, { method: 'POST' });
    await refresh();
  });
  const archive = guarded(async (id) => {
    await api(`/portfolios/${id}`, { method: 'DELETE' });
    if (id === selected) setSelected(null);
    await loadList();
  });
  const restore = guarded(async (id) => {
    await api(`/portfolios/${id}/restore`, { method: 'POST' });
    await loadList();
  });

  const todays = decisions.find((d) => d.asOf === today);
  const field = (key) => ({ value: form[key], onChange: (e) => setForm({ ...form, [key]: e.target.value }) });

  return (
    <main>
      <h1>StockMaxxer</h1>
      <p>
        Simulated trading day: <b>{today || '...'}</b>. Run <code>scripts/nightly.sh</code> to move to the next
        day: it fills accepted recommendations at the open, marks every portfolio at the close, and proposes again.
      </p>
      {error && <p className="error">{error}</p>}

      <h2>New portfolio</h2>
      <form onSubmit={create} className="row">
        <label>Name<input required {...field('name')} /></label>
        <label>Deposit ($)<input required inputMode="decimal" {...field('initialDeposit')} /></label>
        <label>Benchmark<select {...field('benchmark')}>{benchmarks.map((b) => <option key={b}>{b}</option>)}</select></label>
        <label>Engine<select {...field('engine')}>{engines.map((e) => <option key={e.id} value={e.id}>{e.id}</option>)}</select></label>
        <button type="submit">Create</button>
      </form>
      <p className="muted">{engines.find((e) => e.id === form.engine)?.description}</p>

      <h2>
        Portfolios{' '}
        <label className="muted">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} /> show archived
        </label>
      </h2>
      <table>
        <thead>
          <tr><th>Name</th><th>Deposit</th><th>NAV</th><th>Benchmark</th><th>Engine</th><th>Created</th><th /></tr>
        </thead>
        <tbody>
          {portfolios.map((p) => (
            <tr key={p.id} className={p.id === selected ? 'selected' : p.archived ? 'muted' : ''}>
              <td><button className="link" onClick={() => setSelected(p.id)}>{p.name}</button></td>
              <td>{money(p.initialDeposit)}</td>
              <td>{money(navs[p.id])}</td>
              <td>{p.benchmark}</td>
              <td>{p.engine}</td>
              <td>{p.createdOn}</td>
              <td>
                {p.archived
                  ? <button onClick={() => restore(p.id)}>Restore</button>
                  : <button onClick={() => archive(p.id)}>Archive</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {detail && (
        <>
          <h2>{detail.portfolio.name}</h2>
          <p>
            NAV <b>{money(detail.nav)}</b> at the {detail.navDate} close · cash {money(detail.portfolio.cash)} ·
            benchmark {detail.portfolio.benchmark} · engine {detail.portfolio.engine}
          </p>
          {forecast && (
            <p className="note">
              In past {forecast.horizonDays}-day windows, today's holdings would have ended between{' '}
              <b>{money(forecast.p10)}</b> and <b>{money(forecast.p90)}</b> (10th–90th percentile, median {money(forecast.p50)}):
              {' '}{forecast.note}.
            </p>
          )}
          {detail.holdings.length > 0 && (
            <table>
              <thead>
                <tr><th>Holding</th><th>Shares</th><th>Market value</th><th>Cost basis</th><th>% of NAV</th></tr>
              </thead>
              <tbody>
                {detail.holdings.map((h) => (
                  <tr key={h.symbol}>
                    <td>{h.symbol}</td><td>{h.shares}</td><td>{money(h.marketValue)}</td>
                    <td>{money(h.costBasis)}</td><td>{pct(h.weight)}</td>
                  </tr>
                ))}
                <tr><td>Cash</td><td /><td>{money(detail.portfolio.cash)}</td><td /><td>{pct(detail.cashWeight)}</td></tr>
                <tr className="total"><td>Total (NAV)</td><td /><td>{money(detail.nav)}</td><td /><td>100.00%</td></tr>
              </tbody>
            </table>
          )}

          <h3>Recommendation for {today}</h3>
          {todays
            ? <DecisionCard d={todays} onDecide={decide} />
            : <p>None yet. <button onClick={generate}>Generate recommendation</button> or wait for the nightly run.</p>}

          {/* Native <details>: collapsed by default, toggles on click; the key collapses it again per portfolio. */}
          <details key={`history-${selected}`}>
            <summary>History ({decisions.filter((d) => d !== todays).length})</summary>
            {decisions.filter((d) => d !== todays).map((d) => <DecisionCard key={d.id} d={d} />)}
          </details>

          <h3>Equity vs {detail.portfolio.benchmark} (rebased to the deposit)</h3>
          <EquityChart key={`chart-${selected}`} points={equity} benchmark={detail.portfolio.benchmark} />
          {/* The table view: every value in the chart, readable without hovering. */}
          <details>
            <summary className="small">Show as table</summary>
            <table>
              <thead><tr><th>Date</th><th>Portfolio</th><th>{detail.portfolio.benchmark}</th></tr></thead>
              <tbody>
                {equity.map((p) => <tr key={p.date}><td>{p.date}</td><td>{money(p.nav)}</td><td>{money(p.benchmark)}</td></tr>)}
              </tbody>
            </table>
          </details>
        </>
      )}
    </main>
  );
}

// Simplification: a hand-drawn SVG line chart instead of a charting library (no extra dependency).
// One y-axis is honest here: both series are dollars from the same deposit, because the API rebases the
// benchmark to it. Colours are the first two categorical slots: blue = portfolio, orange = benchmark.
const PORTFOLIO_COLOR = '#2a78d6';
const BENCHMARK_COLOR = '#eb6834';
const SIZE = { w: 720, h: 260, left: 64, right: 84, top: 12, bottom: 34 };

/** Round y-axis ticks (steps of 1, 2, 2.5 or 5 x 10^k) that cover min..max in about four steps. */
function niceTicks(min, max) {
  const raw = (max - min) / 4;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= raw);
  const ticks = [];
  for (let i = Math.floor(min / step); i <= Math.ceil(max / step); i++) ticks.push(i * step);
  return { ticks, step };
}

function EquityChart({ points, benchmark }) {
  const [hover, setHover] = useState(null);
  const n = points.length;
  if (n === 0) return null;

  const { w, h, left, right, top, bottom } = SIZE;
  const series = [
    { key: 'benchmark', name: benchmark, color: BENCHMARK_COLOR },
    { key: 'nav', name: 'Portfolio', color: PORTFOLIO_COLOR },   // drawn last, so it sits on top
  ];
  const values = points.flatMap((p) => [Number(p.nav), Number(p.benchmark)]);
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const pad = (hi - lo) * 0.05 || hi * 0.01 || 1;
  const { ticks, step } = niceTicks(lo - pad, hi + pad);
  const yMin = ticks[0];
  const yMax = ticks[ticks.length - 1];
  const plotW = w - left - right;
  const x = (i) => (n === 1 ? left + plotW / 2 : left + (i * plotW) / (n - 1));
  const y = (v) => top + ((yMax - Number(v)) * (h - top - bottom)) / (yMax - yMin);
  const path = (key) => points.map((p, i) => `${i ? 'L' : 'M'}${x(i)},${y(p[key])}`).join(' ');
  const tickLabel = (v) => v.toLocaleString('en-US', {
    style: 'currency', currency: 'USD', maximumFractionDigits: Number.isInteger(step) ? 0 : 2,
  });
  const day = (d) => new Date(`${d}T00:00:00`).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  const xTicks = [...new Set(n <= 6
    ? points.map((_, i) => i)
    : [0, 1, 2, 3, 4].map((k) => Math.round((k * (n - 1)) / 4)))];
  const endY = series.map((s) => y(points[n - 1][s.key]));
  const endLabels = Math.abs(endY[0] - endY[1]) >= 14;   // colliding end labels are dropped, not stacked
  const at = hover == null ? null : Math.min(hover, n - 1);

  // The crosshair snaps to the nearest day, so the pointer never has to land on a 2px line.
  const onMove = (e) => {
    const box = e.currentTarget.getBoundingClientRect();
    const px = ((e.clientX - box.left) / box.width) * w;
    setHover(n === 1 ? 0 : Math.max(0, Math.min(n - 1, Math.round(((px - left) / plotW) * (n - 1)))));
  };
  const onKey = (e) => {
    if (e.key === 'ArrowLeft') setHover((i) => Math.max(0, (i ?? n - 1) - 1));
    if (e.key === 'ArrowRight') setHover((i) => Math.min(n - 1, (i ?? n - 1) + 1));
  };

  return (
    <div className="chart">
      <div className="legend">
        {[...series].reverse().map((s) => (
          <span key={s.key}><span className="key" style={{ background: s.color }} />{s.name}</span>
        ))}
      </div>
      <svg
        viewBox={`0 0 ${w} ${h}`}
        role="img"
        tabIndex={0}
        aria-label={`Portfolio value versus ${benchmark}, ${day(points[0].date)} to ${day(points[n - 1].date)}. `
          + 'Use the left and right arrow keys to read each day.'}
        onPointerMove={onMove}
        onPointerLeave={() => setHover(null)}
        onFocus={() => setHover(n - 1)}
        onBlur={() => setHover(null)}
        onKeyDown={onKey}
      >
        {ticks.map((v, i) => (
          <g key={v}>
            <line x1={left} x2={w - right} y1={y(v)} y2={y(v)} stroke={i === 0 ? '#c3c2b7' : '#e1e0d9'} />
            <text x={left - 8} y={y(v)} dy="0.32em" textAnchor="end" className="axis">{tickLabel(v)}</text>
          </g>
        ))}
        {xTicks.map((i) => (
          <text key={i} x={x(i)} y={h - bottom + 20} className="axis"
            textAnchor={n > 1 && i === 0 ? 'start' : n > 1 && i === n - 1 ? 'end' : 'middle'}>{day(points[i].date)}</text>
        ))}
        {series.map((s) => (
          <path key={s.key} d={path(s.key)} fill="none" stroke={s.color} strokeWidth="2"
            strokeLinejoin="round" strokeLinecap="round" />
        ))}
        {at != null && <line x1={x(at)} x2={x(at)} y1={top} y2={h - bottom} stroke="#898781" />}
        {series.map((s) => {
          const i = at ?? n - 1;   // the end dots, or the hovered day's dots
          return <circle key={s.key} cx={x(i)} cy={y(points[i][s.key])} r="4" fill={s.color} stroke="#fff" strokeWidth="2" />;
        })}
        {endLabels && at == null && series.map((s, k) => (
          <text key={s.key} x={x(n - 1) + 10} y={endY[k]} dy="0.32em" className="end">{money(points[n - 1][s.key])}</text>
        ))}
      </svg>
      {at != null && (
        <div
          className="tip"
          style={{
            left: `${(x(at) / w) * 100}%`,
            transform: x(at) > w * 0.6 ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)',
          }}
        >
          <div className="muted">{day(points[at].date)}</div>
          {[...series].reverse().map((s) => (
            <div key={s.key}>
              <span className="key" style={{ background: s.color }} /><b>{money(points[at][s.key])}</b>{' '}
              <span className="muted">{s.name}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function DecisionCard({ d, onDecide }) {
  return (
    <div className="card">
      <p>
        <b>{d.asOf}</b> · {d.engine} · <b>{d.status}</b>: {STATUS_HELP[d.status]}
        {d.fillDate && ` (filled at the ${d.fillDate} open)`}
        {d.status === 'PROPOSED' && onDecide && (
          <>
            {' '}<button onClick={() => onDecide(d.id, 'accept')}>Accept</button>{' '}
            <button onClick={() => onDecide(d.id, 'ignore')}>Ignore</button>
          </>
        )}
      </p>
      <table>
        <thead>
          <tr>
            <th>Symbol</th><th>Current</th><th>Target</th><th>Action</th>
            <th>{d.engine === 'quant_llm' ? 'Severity' : ''}</th><th>Explanation</th>
          </tr>
        </thead>
        <tbody>
          {d.lines.map((l) => (
            <tr key={l.symbol}>
              <td>{l.symbol}</td><td>{pct(l.priorWeight)}</td><td>{pct(l.targetWeight)}</td><td>{l.action}</td>
              <td>{l.severity || ''}</td><td className="why">{l.explanation}{l.quote && <blockquote className="quote">“{l.quote}”</blockquote>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
