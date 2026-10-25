// Garante que a parametrização do Bootstrap (src/styles/bootstrap/_variables*.scss) só redefine
// variáveis documentadas (declaradas com !default no Bootstrap instalado). Rodar ao atualizar o
// Bootstrap: uma variável removida ou renomeada na nova versão aparece aqui como erro.
// Uso: node scripts/check-bootstrap-variables.mjs
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const bootstrap = join(root, 'node_modules', 'bootstrap', 'scss');
const pairs = [
  ['src/styles/bootstrap/_variables.scss', ['_variables.scss']],
  ['src/styles/bootstrap/_variables-dark.scss', ['_variables-dark.scss', '_variables.scss']],
];

const declared = (file) => {
  const names = new Set();
  for (const match of readFileSync(join(bootstrap, file), 'utf8').matchAll(/^\$([\w-]+):[^;]*!default/gm)) {
    names.add(match[1]);
  }
  return names;
};

let failures = 0;
let count = 0;
for (const [own, sources] of pairs) {
  const known = new Set(sources.flatMap((s) => [...declared(s)]));
  for (const match of readFileSync(join(root, own), 'utf8').matchAll(/^\$([\w-]+):/gm)) {
    count++;
    if (!known.has(match[1])) {
      failures++;
      console.error(`${own}: $${match[1]} não é variável documentada do Bootstrap ${sources.join(' / ')}`);
    }
  }
}
const version = JSON.parse(readFileSync(join(root, 'node_modules', 'bootstrap', 'package.json'), 'utf8')).version;
if (failures) {
  console.error(`${failures} variável(is) inválida(s) para o Bootstrap ${version}.`);
  process.exit(1);
}
console.log(`Bootstrap ${version}: ${count} variáveis sobrescritas, todas documentadas.`);
