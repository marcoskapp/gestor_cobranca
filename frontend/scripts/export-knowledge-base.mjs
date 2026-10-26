// Gera a base de conhecimento do chat de suporte a partir do manual contextual (fonte única, em TypeScript)
// e grava o JSON versionado que o backend lê do classpath (docs/tecnico/desenho/chat-suporte.md §4).
// Uso: node scripts/export-knowledge-base.mjs          → regrava o arquivo
//      node scripts/export-knowledge-base.mjs --check  → falha se o arquivo estiver desatualizado (prebuild/pretest)
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const entry = join(root, 'src', 'app', 'core', 'onboarding', 'manual', 'knowledge-export.ts');
const target = join(root, '..', 'backend', 'src', 'main', 'resources', 'helpdesk', 'knowledge-base.json');
const check = process.argv.includes('--check');

const work = mkdtempSync(join(tmpdir(), 'gc-knowledge-'));
try {
  const out = join(work, 'knowledge.mjs');
  // só dados: o conteúdo do manual, o registro de telas e o menu não importam Angular
  await build({ entryPoints: [entry], bundle: true, format: 'esm', platform: 'node', outfile: out, logLevel: 'error' });
  const { buildKnowledgeExport } = await import(pathToFileURL(out).href);
  const data = await buildKnowledgeExport();
  const body = JSON.stringify(data);
  const version = createHash('sha256').update(body).digest('hex').slice(0, 16);
  const text = JSON.stringify({ generatedBy: 'frontend/scripts/export-knowledge-base.mjs', contentVersion: version, ...data }, null, 1) + '\n';
  if (check) {
    let current = '';
    try {
      current = readFileSync(target, 'utf8');
    } catch {
      // ausente: desatualizado
    }
    if (current !== text) {
      console.error(`Base de conhecimento do chat de suporte desatualizada (${target}).\nRode: npm run knowledge`);
      process.exit(1);
    }
    console.log(`Base de conhecimento do chat de suporte em dia (versão ${version}).`);
  } else {
    writeFileSync(target, text);
    console.log(`Base de conhecimento gravada: ${data.modules.length} módulos, ${data.screens.length} telas, ${data.glossary.length} termos (versão ${version}).`);
  }
} finally {
  rmSync(work, { recursive: true, force: true });
}
