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
      return;
    }
    const [d, ds, eq] = await Promise.all([
      api(`/portfolios/${id}`),
      api(`/portfolios/${id}/decisions`),
      api(`/portfolios/${id}/equity`),
    ]);
    setDetail(d);
    setDecisions(ds);
    setEquity(eq);
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
          {detail.holdings.length > 0 && (
            <table>
              <thead><tr><th>Holding</th><th>Shares</th></tr></thead>
              <tbody>{detail.holdings.map((h) => <tr key={h.symbol}><td>{h.symbol}</td><td>{h.shares}</td></tr>)}</tbody>
            </table>
          )}

          <h3>Recommendation for {today}</h3>
          {todays
            ? <DecisionCard d={todays} onDecide={decide} />
            : <p>None yet. <button onClick={generate}>Generate recommendation</button> or wait for the nightly run.</p>}

          {/* Native <details>: collapsed by default, toggles on click; the key collapses it again per portfolio. */}
          <details key={selected}>
            <summary>History ({decisions.filter((d) => d !== todays).length})</summary>
            {decisions.filter((d) => d !== todays).map((d) => <DecisionCard key={d.id} d={d} />)}
          </details>

          <h3>Equity vs {detail.portfolio.benchmark} (rebased to the deposit)</h3>
          <table>
            <thead><tr><th>Date</th><th>Portfolio</th><th>{detail.portfolio.benchmark}</th></tr></thead>
            <tbody>
              {equity.map((p) => <tr key={p.date}><td>{p.date}</td><td>{money(p.nav)}</td><td>{money(p.benchmark)}</td></tr>)}
            </tbody>
          </table>
        </>
      )}
    </main>
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
            <th>{d.engine === 'quant_llm' ? 'Severity (simulated)' : ''}</th>
          </tr>
        </thead>
        <tbody>
          {d.lines.map((l) => (
            <tr key={l.symbol}>
              <td>{l.symbol}</td><td>{pct(l.priorWeight)}</td><td>{pct(l.targetWeight)}</td><td>{l.action}</td>
              <td>{l.severity || ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
