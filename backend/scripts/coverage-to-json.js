// Writes COVERAGE.json (repo root) from backend/coverage/coverage-summary.json.
// Each number is the measured line coverage of backend/src/domain/<area>/, truncated to one decimal
// so a declared figure can never exceed the real one.
const fs = require('fs');
const path = require('path');

const AREAS = ['fixtures', 'scoring', 'standings', 'store', 'roster', 'rbac'];
const summaryPath = path.join(__dirname, '..', 'coverage', 'coverage-summary.json');
const outPath = path.join(__dirname, '..', '..', 'COVERAGE.json');

if (!fs.existsSync(summaryPath)) {
  console.error('coverage-summary.json not found; run jest with --coverage first');
  process.exit(1);
}

const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
const totals = Object.fromEntries(AREAS.map((a) => [a, { covered: 0, total: 0 }]));

for (const [file, data] of Object.entries(summary)) {
  if (file === 'total') continue;
  const parts = file.split(path.sep === '\\' ? /[\\/]/ : '/');
  const i = parts.lastIndexOf('domain');
  const area = i >= 0 ? parts[i + 1] : null;
  if (totals[area]) {
    totals[area].covered += data.lines.covered;
    totals[area].total += data.lines.total;
  }
}

const result = {};
for (const area of AREAS) {
  const { covered, total } = totals[area];
  result[area] = total === 0 ? 0 : Math.floor((covered / total) * 1000) / 10;
}

fs.writeFileSync(outPath, JSON.stringify(result) + '\n');
console.log('COVERAGE.json', JSON.stringify(result));
