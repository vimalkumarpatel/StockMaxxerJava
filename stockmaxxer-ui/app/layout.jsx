export const metadata = { title: 'StockMaxxer' };

const css = `
  body { font-family: system-ui, sans-serif; margin: 24px auto; max-width: 960px; padding: 0 16px; color: #222; }
  table { border-collapse: collapse; margin: 8px 0; }
  th, td { padding: 3px 10px; text-align: left; border-bottom: 1px solid #ddd; }
  .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: end; }
  .row label { display: flex; flex-direction: column; font-size: 14px; }
  .muted { color: #777; font-size: 14px; font-weight: normal; }
  .selected { background: #eef4ff; }
  .card { border: 1px solid #ccc; border-radius: 6px; padding: 8px 12px; margin: 8px 0; }
  .error { color: #b00020; }
  .total td { font-weight: 600; border-top: 1px solid #c3c2b7; }
  td.why { min-width: 320px; max-width: 520px; font-size: 13px; line-height: 1.4; color: #52514e; }
  blockquote.quote { margin: 6px 0 0; padding-left: 8px; border-left: 3px solid #d6d3cc; font-style: italic; }
  summary { font-size: 1.17em; font-weight: bold; margin: 1em 0; cursor: pointer; }
  summary.small { font-size: 14px; font-weight: normal; margin: 4px 0; color: #52514e; }
  .chart { position: relative; max-width: 760px; }
  .chart svg { display: block; width: 100%; height: auto; overflow: visible; }
  .chart svg:focus-visible { outline: 2px solid #2a78d6; outline-offset: 4px; }
  .chart .axis { fill: #898781; font-size: 12px; font-variant-numeric: tabular-nums; }
  .chart .end { fill: #52514e; font-size: 12px; font-variant-numeric: tabular-nums; }
  .legend { display: flex; gap: 16px; font-size: 14px; color: #52514e; margin: 4px 0 8px; }
  .key { display: inline-block; width: 14px; height: 2px; border-radius: 1px; vertical-align: middle; margin-right: 6px; }
  .tip { position: absolute; top: 32px; pointer-events: none; white-space: nowrap; background: #fff; font-size: 13px;
         border: 1px solid rgba(11, 11, 11, 0.10); border-radius: 6px; padding: 6px 10px; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08); }
  .link { background: none; border: none; padding: 0; color: #0645ad; cursor: pointer; text-decoration: underline; font: inherit; }
`;

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>
        <style>{css}</style>
        {children}
      </body>
    </html>
  );
}
