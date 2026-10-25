// Gera o sprite SVG dos ícones da aplicação a partir do pacote `bootstrap-icons` (MIT), só com os
// ícones listados em src/app/shared/components/icon/icon-names.ts. O sprite é servido pela própria
// aplicação (sem CDN, compatível com a CSP) e referenciado por `<use href="…#nome">` no `app-icon`.
//
// Uso: node scripts/build-icon-sprite.mjs           (gera/atualiza os arquivos)
//      node scripts/build-icon-sprite.mjs --check   (falha se os arquivos estiverem desatualizados ou
//                                                    se um template usar ícone fora do sprite)
// Requer Node com remoção de tipos habilitada por padrão (>= 22.18), já exigido em `engines`.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
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

// Nomes literais de ícone nos templates (`<app-icon name>`, `icon`, `emptyIcon`, `icon: '…'`): um nome
// fora do sprite não desenha nada (o `app-icon` não tem fallback), então falha aqui, no build.
const known = new Set(APP_ICONS);
const iconUsages = [
  /<app-icon\b[^>]*?\sname="([^"{}]+)"/g,
  /<app-icon\b[^>]*?\[name\]="'([^']+)'"/g,
  /\s(?:icon|emptyIcon)="([^"{}]+)"/g,
  /\b(?:icon|emptyIcon)\s*:\s*'([^']+)'/g,
];
const sourceFiles = (dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.(ts|html)$/.test(entry.name) && !entry.name.endsWith('.spec.ts') ? [path] : [];
  });
const unknownIcons = sourceFiles(join(root, 'src')).flatMap((file) => {
  const text = readFileSync(file, 'utf8');
  return iconUsages.flatMap((pattern) =>
    [...text.matchAll(pattern)]
      .filter((match) => !known.has(match[1]))
      .map((match) => `${file.slice(root.length + 1)}:${text.slice(0, match.index).split('\n').length}: "${match[1]}"`),
  );
});

if (check) {
  if (outdated.length) {
    console.error(`Sprite de ícones desatualizado: rode "npm run icons" (${outdated.map(([p]) => p).join(', ')}).`);
    process.exit(1);
  }
  if (unknownIcons.length) {
    console.error(`Ícones fora do sprite (inclua em icon-names.ts e rode "npm run icons"):\n${unknownIcons.join('\n')}`);
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
