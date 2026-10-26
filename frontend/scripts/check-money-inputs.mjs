// Garante que todo campo monetário digitado usa a máscara `appMoney` (docs/tecnico/desenho/ux.md, §13.2).
// Recusa, nos templates (.html e templates inline dos .ts, exceto specs):
//  1. `appMoney` com `type="number"` ou `inputmode` próprio (a diretiva já define `type="text"` e
//     `inputmode="numeric"`; o `type="number"` do navegador desfaz a máscara);
//  2. campo sem `appMoney` cujo rótulo (`label` do `app-field` logo acima ou `aria-label`) tem "R$" —
//     o símbolo vai no `input-group-text`, não no rótulo nem no texto do campo;
//  3. campo numérico sem `appMoney` (`type="number"` ou `inputmode="decimal"`) ligado a um nome de
//     valor monetário (amount, balance, fee, cost, price, threshold…) que não seja percentual/taxa.
// Exceção consciente: comentário `not-money: <motivo>` na linha do campo ou na anterior.
// Uso: node scripts/check-money-inputs.mjs
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const app = join(root, 'src', 'app');

const MONEY_NAME = /(amount|balance|valor|saldo|price|preco|fee\b|fees\b|cost|custo|honorarium|downpayment|threshold|periodcap|budget|target)/i;
const NOT_MONEY_NAME = /(percent|pct|rate|share|factor|ratio|weight|count|days|hours|minutes|seconds|installments|points|score|level|priority|tokens)/i;

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return files(path);
    }
    return /\.(html|ts)$/.test(name) && !name.endsWith('.spec.ts') ? [path] : [];
  });
}

const problems = [];
let checked = 0;
for (const file of files(app)) {
  const text = readFileSync(file, 'utf8');
  for (const match of text.matchAll(/<input\b[^>]*?>/gs)) {
    const tag = match[0];
    if (/type="(checkbox|radio|file|date|time|datetime-local|email|password|search|color|range|hidden)"/.test(tag)) {
      continue;
    }
    const line = text.slice(0, match.index).split('\n').length;
    const before = text.slice(Math.max(0, match.index - 400), match.index);
    const lines = text.split('\n');
    const nearby = `${lines[line - 2] ?? ''}\n${tag}`;
    if (/not-money:/.test(nearby)) {
      continue;
    }
    const where = `${relative(root, file)}:${line}`;
    const money = /\bappMoney\b/.test(tag);
    if (money) {
      checked++;
      if (/\stype="number"|\s\[type\]=|\sinputmode=|\s\[attr\.inputmode\]=/.test(tag)) {
        problems.push(`${where}: appMoney não combina com type="number"/inputmode (a diretiva já define o teclado).`);
      }
      continue;
    }
    // rótulo do app-field imediatamente anterior (até 3 linhas acima) ou aria-label do próprio campo
    const labelLines = before.split('\n').slice(-4).join('\n');
    const labels = [...labelLines.matchAll(/(?:^|\s)\[?label\]?="([^"]*)"/g)].map((m) => m[1]);
    const label = labels.at(-1) ?? '';
    const aria = /aria-label\]?="([^"]*)"/.exec(tag)?.[1] ?? '';
    if (/R\$/.test(label) || /R\$/.test(aria)) {
      problems.push(`${where}: campo com "R$" no rótulo sem appMoney ("${label || aria}").`);
      continue;
    }
    const numeric = /\stype="number"|\sinputmode="decimal"/.test(tag);
    const name = /(?:formControlName|\[formControl\]|\[\(ngModel\)\]|\[ngModel\])="([^"]+)"/.exec(tag)?.[1] ?? '';
    if (numeric && MONEY_NAME.test(name) && !NOT_MONEY_NAME.test(name)) {
      problems.push(`${where}: "${name}" parece valor monetário; use appMoney (ou marque "not-money: motivo").`);
    }
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`${problems.length} campo(s) monetário(s) fora do padrão. Veja docs/tecnico/desenho/ux.md (§13.2).`);
  process.exit(1);
}
console.log(`Campos monetários: ${checked} com appMoney, nenhum fora do padrão.`);
