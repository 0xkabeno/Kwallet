// v3.99.5 (S2/S6): Kwallet and Hwallet must carry the same NEAR fee code, and it must give the protocol's numbers
const fs = require('fs');
function grab(f) { const s = fs.readFileSync(f, 'utf8'); const i = s.indexOf('  function nearCost('); if (i < 0) throw new Error('nearCost missing in ' + f); const j = s.indexOf('\n  }\n', i); return s.slice(i, j + 4); }
const k = grab('web/index.html'), h = grab('hwallet/web/index.html');
if (k !== h) { console.error('::error::nearCost differs between Kwallet and Hwallet'); process.exit(1); }
const nearCost = new Function(k + '; return nearCost;')();
const near = (x) => Number(x) / 1e24, d = { gasPrice: '100000000' };
const cases = [
  ['new implicit account', nearCost(d, 0, true), 0.0070, 0.0072, 0.0079, 0.0082],
  ['existing account', nearCost(d, 0, false), 0.00003, 0.00006, 0.0002, 0.0003],
  ['one token transfer', nearCost(d, 1, false), 0.002, 0.004, 0.029, 0.032]
];
let bad = 0;
for (const [n, r, f0, f1, n0, n1] of cases) {
  const f = near(r.fee), q = near(r.need), ok = f >= f0 && f <= f1 && q >= n0 && q <= n1 && r.need >= r.fee;
  console.log((ok ? 'ok  ' : 'BAD ') + n + ': fee ' + f.toFixed(6) + ' NEAR, needs ' + q.toFixed(6) + ' NEAR'); if (!ok) bad = 1;
}
process.exit(bad);
