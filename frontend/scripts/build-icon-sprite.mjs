// Gera o sprite SVG dos ícones da aplicação a partir do pacote `bootstrap-icons` (MIT), só com os
// ícones listados em src/app/shared/components/icon/icon-names.ts. O sprite é servido pela própria
// aplicação (sem CDN, compatível com a CSP) e referenciado por `<use href="…#nome">` no `app-icon`.
//
// Uso: node scripts/build-icon-sprite.mjs           (gera/atualiza os arquivos)
//      node scripts/build-icon-sprite.mjs --check   (falha se os arquivos estiverem desatualizados)
// Requer Node com remoção de tipos habilitada por padrão (>= 22.18), já exigido em `engines`.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { APP_ICONS } from '../src/app/shared/components/icon/icon-names.ts';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'node_modules', 'bootstrap-icons', 'icons');
const spritePath = join(root, 'public', 'icons', 'app-icons.svg');
const constantPath = join(root, 'src', 'app', 'shared', 'components', 'icon', 'icon-sprite.ts');
const check = process.argv.includes('--check');

const symbols = [...new Set(APP_ICONS)].sort().map((name) => {
  const file = join(source, `${name}.svg`);
  if (!existsSync(file)) {
    throw new Error(`Ícone "${name}" não existe em bootstrap-icons (${file}).`);
  }
  const svg = readFileSync(file, 'utf8');
  const viewBox = /viewBox="([^"]+)"/.exec(svg)?.[1] ?? '0 0 16 16';
  const body = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '').trim();
  return `<symbol id="${name}" viewBox="${viewBox}">${body}</symbol>`;
});

const version = JSON.parse(readFileSync(join(root, 'node_modules', 'bootstrap-icons', 'package.json'), 'utf8')).version;
const sprite =
  `<svg xmlns="http://www.w3.org/2000/svg">` +
  `<!-- Bootstrap Icons ${version} (MIT, https://icons.getbootstrap.com). Gerado por scripts/build-icon-sprite.mjs; não editar. -->` +
  `${symbols.join('')}</svg>\n`;
const hash = createHash('sha256').update(sprite).digest('hex').slice(0, 10);
const constant =
  `// Gerado por scripts/build-icon-sprite.mjs; não editar. O parâmetro muda quando o sprite muda\n` +
  `// (invalida o cache do navegador sem nome de arquivo com hash).\n` +
  `export const ICON_SPRITE_URL = 'icons/app-icons.svg?v=${hash}';\n`;

const outdated = [
  [spritePath, sprite],
  [constantPath, constant],
].filter(([path, content]) => !existsSync(path) || readFileSync(path, 'utf8') !== content);

if (check) {
  if (outdated.length) {
    console.error(`Sprite de ícones desatualizado: rode "npm run icons" (${outdated.map(([p]) => p).join(', ')}).`);
    process.exit(1);
  }
  console.log(`Sprite de ícones em dia (${symbols.length} ícones).`);
} else {
  mkdirSync(dirname(spritePath), { recursive: true });
  for (const [path, content] of outdated) {
    writeFileSync(path, content);
  }
  console.log(`Sprite de ícones: ${symbols.length} ícones, ${(sprite.length / 1024).toFixed(1)} kB (${outdated.length ? 'atualizado' : 'sem mudanças'}).`);
}
