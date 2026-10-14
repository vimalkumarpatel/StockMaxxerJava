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
  summary { font-size: 1.17em; font-weight: bold; margin: 1em 0; cursor: pointer; }
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
