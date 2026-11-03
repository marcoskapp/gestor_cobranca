// Garante a informação contextual dos indicadores (docs/tecnico/desenho/ux.md §8.6):
//  1. todo id de indicador usado nos templates e nas colunas de tabela existe no catálogo
//     (`src/app/core/onboarding/manual/indicators/content/`);
//  2. todo cartão de indicador (`<app-kpi-card`) tem `info` — card de KPI novo não entra sem explicação;
//  3. todo `<app-info-hint>` tem `indicator` e `label`.
// Formas reconhecidas: `indicator="x.y"`, `info="x.y"`, `[info]="'x.y'"`, `[indicator]="'x.y'"` e
// `info: 'x.y'` (definição de coluna `TableColumn`). Ids dinâmicos (`[info]="expr"`) precisam de um
// comentário `info-hint: <id>[, <id>…]` na mesma linha ou na anterior, para entrarem na conferência.
// Uso: node scripts/check-info-hints.mjs
import { build } from 'esbuild';
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const app = join(root, 'src', 'app');
const entry = join(app, 'core', 'onboarding', 'manual', 'indicators', 'indicator-index.ts');

async function catalog() {
  const work = mkdtempSync(join(tmpdir(), 'gc-hints-'));
  try {
    const out = join(work, 'indicators.mjs');
    await build({ entryPoints: [entry], bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error' });
    const { loadAllIndicators } = await import(pathToFileURL(out).href);
    return new Set((await loadAllIndicators()).map((i) => i.id));
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return files(path);
    }
    return /\.(html|ts)$/.test(name) && !name.endsWith('.spec.ts') ? [path] : [];
  });
}

const ids = await catalog();
const problems = [];
const used = new Map();
let cards = 0;

const lineOf = (text, index) => text.slice(0, index).split('\n').length;

for (const file of files(app)) {
  if (file.includes(join('manual', 'indicators'))) {
    continue;
  }
  const text = readFileSync(file, 'utf8');
  const rel = relative(root, file);
  const patterns = [
    /\b(?:indicator|info)="([a-z0-9]+(?:[.-][a-z0-9]+)+)"/g,
    /\[(?:indicator|info)\]="'([a-z0-9]+(?:[.-][a-z0-9]+)+)'"/g,
    /\binfo:\s*'([a-z0-9]+(?:[.-][a-z0-9]+)+)'/g,
    /info-hint:[ \t]*([a-z0-9.,\- \t]+)/g,
  ];
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) {
      for (const id of match[1].split(/[,\s]+/).filter((s) => s.includes('.'))) {
        const where = `${rel}:${lineOf(text, match.index)}`;
        used.set(id, [...(used.get(id) ?? []), where]);
        if (!ids.has(id)) {
          problems.push(`${where}: indicador "${id}" não existe no catálogo (core/onboarding/manual/indicators/content/).`);
        }
      }
    }
  }
  for (const match of text.matchAll(/<app-kpi-card\b[^>]*>/gs)) {
    cards++;
    if (!/\s\[?info\]?=/.test(match[0])) {
      problems.push(`${rel}:${lineOf(text, match.index)}: cartão de indicador sem informação contextual (acrescente info="<id do catálogo>").`);
    }
  }
  for (const match of text.matchAll(/<app-info-hint\b[^>]*>/gs)) {
    if (!/\s\[?indicator\]?=/.test(match[0]) || !/\s\[?label\]?=/.test(match[0])) {
      problems.push(`${rel}:${lineOf(text, match.index)}: app-info-hint precisa de indicator e label.`);
    }
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`${problems.length} problema(s) na informação contextual dos indicadores. Veja docs/tecnico/desenho/ux.md (§8.6).`);
  process.exit(1);
}
console.log(`Informação contextual: ${used.size} indicadores usados nas telas (de ${ids.size} no catálogo), ${cards} cartões de indicador, todos com explicação.`);
