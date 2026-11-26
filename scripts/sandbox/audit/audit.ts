// =============================================================================
// Auditoria da interface do sandbox, perfil a perfil (decisão 688; docs/tecnico/operacao/sandbox.md §Auditoria).
//
// Para cada perfil: entra com o usuário de demonstração, percorre o menu lateral, os atalhos da página inicial,
// as opções extras da busca "Buscar ou ir para", as abas de cada página e (AUDIT_DETAIL=1) o primeiro registro de
// cada lista com as abas dele. Em cada página visitada verifica:
//   - 403          resposta 403 da API (a tela chamou um endpoint que o perfil não pode usar);
//   - acesso-negado aviso "Acesso negado" (toast) ou a página de acesso negado da rota;
//   - cartoes      cartões irmãos na mesma linha (sobreposição vertical relevante) com topos diferentes (> 1 px);
//   - rolagem      o documento rola (scrollHeight > innerHeight ou scrollWidth > innerWidth): só a área de
//                  conteúdo (`main.app-content`) pode rolar; ou uma tabela (`.table-responsive`) fica mais larga
//                  que o espaço dela e ganha barra horizontal (texto longo sem quebra, como IPv6 e rotas);
//   - rolagem-interna  contêiner com barra vertical que não deveria existir (ux.md §23): sobra de até 4 px (a "barra
//                  fantasma") ou bloco que rola na horizontal e também na vertical sem altura máxima;
//   - erro-js      exceção não tratada no navegador.
// Ação visível e desabilitada com o motivo (`app-disabled-action`, exceção decidida pelo responsável) não é problema:
// ela não chama o servidor nem mostra "Acesso negado"; só os 403 e os avisos "Acesso negado" contam.
// Saída: relatorio.md (perfil × página) e resultado.json em AUDIT_OUT; código de saída 0 sem problemas, 1 com
// problemas, 2 quando a auditoria não conseguiu rodar (login, sandbox fora do ar).
//
// Portal do credor (fatia 8): os perfis "assessoria/<pessoa do credor>" (ex.: aurora/hzn-financeiro) entram em
// /credor/<assessoria>/entrar com a senha e o código do autenticador (TOTP calculado aqui com o segredo do cofre do
// sandbox, lido do arquivo temporário AUDIT_CREDITOR_SECRETS_FILE — 600, fora do repositório, montado só leitura; o
// segredo nunca vem por variável de ambiente nem argumento e nunca é impresso). Cada pessoa usa um código por execução,
// num passo de 30 s posterior ao início da auditoria (o servidor recusa o reuso e conta como falha); código recusado
// nunca é repetido (no máximo uma nova tentativa, com passo novo). Percorre Início, prestação de contas e a ficha,
// contestações e a ficha, pagamentos e atividade, com as mesmas checagens (o documento do portal rola por desenho: só a
// rolagem horizontal conta). Não aciona nenhuma ação (aceite, contestação, informe, resposta, "Tentar novamente", sair
// pelo menu); a sessão é encerrada ao fim pela API, como a dos perfis internos.
// Variáveis: APP_URL, AUDIT_PROFILES ("aurora/admin boreal/advogado …"), AUDIT_PARALLEL (padrão 3),
// AUDIT_DETAIL (padrão 1), AUDIT_WIDTH/AUDIT_HEIGHT (padrão 1280×800), AUDIT_OUT, SANDBOX_DEMO_PASSWORD,
// AUDIT_CREDITOR_SECRETS_FILE.
// Respeita os limites do sandbox: uma sessão por perfil (encerrada ao fim, o limite é 3 por usuário) e logins
// espaçados (o limite é 20 por minuto por IP; 429 espera e tenta de novo).
// =============================================================================
import { chromium } from 'playwright';
import type { Browser, Page } from 'playwright';
import { createHmac } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

type ProblemType = '403' | 'acesso-negado' | 'cartoes' | 'rolagem' | 'rolagem-interna' | 'erro-js';

interface Problem {
  profile: string;
  where: string;
  type: ProblemType;
  detail: string;
}

interface ProfileResult {
  profile: string;
  pages: string[];
  problems: Problem[];
  failure: string | null;
}

interface Target {
  kind: 'menu' | 'inicio';
  label: string;
  href: string;
}

const BASE = (process.env.APP_URL ?? 'http://localhost:58080').replace(/\/$/, '');
const PASSWORD = process.env.SANDBOX_DEMO_PASSWORD ?? 'Sandbox@2026!';
const PARALLEL = Math.max(1, Number(process.env.AUDIT_PARALLEL ?? '3'));
const DETAIL = (process.env.AUDIT_DETAIL ?? '1') === '1';
const WIDTH = Number(process.env.AUDIT_WIDTH ?? '1280');
const HEIGHT = Number(process.env.AUDIT_HEIGHT ?? '800');
const OUT = process.env.AUDIT_OUT ?? 'sandbox-audit';
const DEFAULT_PROFILES = [
  'admin', 'gestor', 'supervisor', 'operador', 'operador2', 'financeiro', 'auditor', 'juridico', 'advogado',
  'analista', 'estrategia', 'qualidade', 'compliance', 'integracao',
].map((p) => `aurora/${p}`).concat(['boreal/operador2', 'boreal/advogado']);
const PROFILES = (process.env.AUDIT_PROFILES ?? '').split(/[\s,]+/).filter(Boolean);

// Pessoas do credor de demonstração: "assessoria/pessoa" → chave hexadecimal do autenticador (só em memória).
const CREDITOR_KEYS = new Map<string, string>();
const secretsFile = process.env.AUDIT_CREDITOR_SECRETS_FILE ?? '';
if (secretsFile && existsSync(secretsFile)) {
  for (const line of readFileSync(secretsFile, 'utf8').split('\n')) {
    const [tenant, email, , , hex] = line.split('\t');
    if (!line.startsWith('#') && tenant && email && /^[0-9a-f]{20,}$/.test(hex ?? '')) {
      CREDITOR_KEYS.set(`${tenant}/${email.split('@')[0]}`, hex);
    }
  }
}
const DEFAULT_CREDITOR_PROFILES = ['aurora/hzn-gestao', 'aurora/hzn-financeiro', 'aurora/hzn-juridico',
  'aurora/hzn-compliance', 'aurora/hzn-consulta', 'aurora/est-consulta', 'boreal/csr-gestao']
  .filter((p) => CREDITOR_KEYS.has(p));
const TOTP_STEP_S = 30;
const RUN_STEP = Math.floor(Date.now() / 1000 / TOTP_STEP_S);

/** Código TOTP (RFC 6238, HMAC-SHA1, 6 dígitos) do passo. */
function totp(hexKey: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hash = createHmac('sha1', Buffer.from(hexKey, 'hex')).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

/** Espera um passo posterior a `after` (e ao início da execução) e devolve o passo atual. */
async function freshStep(after: number): Promise<number> {
  const floor = Math.max(after, RUN_STEP);
  for (;;) {
    const now = Date.now() / 1000;
    const step = Math.floor(now / TOTP_STEP_S);
    if (step > floor) return step;
    await new Promise((r) => setTimeout(r, ((step + 1) * TOTP_STEP_S - now) * 1000 + 300));
  }
}

const TAB_SELECTOR = 'main [role="tab"], main .nav-tabs .nav-link, main .nav-underline .nav-link';
const LOGIN_SPACING_MS = 3500; // ≤ 17 logins por minuto, abaixo do limite de 20 por IP
const ALIGN_TOLERANCE_PX = 1;

// Logins em fila única, espaçados, para todos os perfis em paralelo.
let loginQueue: Promise<void> = Promise.resolve();
function spacedLogin(): Promise<void> {
  const turn = loginQueue.then(() => new Promise<void>((r) => setTimeout(r, LOGIN_SPACING_MS)));
  loginQueue = turn;
  return turn;
}

/** Rota sem ids, para agrupar a mesma página de registros diferentes. */
function normalizePath(path: string): string {
  return path.replace(/[0-9a-f]{8}-[0-9a-f-]{20,}/gi, ':id').replace(/\/\d+(?=\/|$|\?)/g, '/:n');
}

/** Cartões desalinhados, rolagem do documento e tabelas, rolagem interna fora do padrão (dentro de `root`). */
async function layoutOf(page: Page, root: string) {
  return page.evaluate(([tolerance, root]) => {
    const doc = document.documentElement;
    const misaligned: string[] = [];
    for (const parent of new Set([...document.querySelectorAll('.app-card')].map((c) => c.parentElement))) {
      if (!parent) continue;
      const cards = [...parent.children].filter((c) => c.classList.contains('app-card'))
        .map((c) => ({ el: c, r: c.getBoundingClientRect() })).filter((c) => c.r.width > 0 && c.r.height > 0);
      for (let i = 0; i < cards.length; i++) {
        for (let j = i + 1; j < cards.length; j++) {
          const a = cards[i].r;
          const b = cards[j].r;
          const sideBySide = Math.min(a.right, b.right) <= Math.max(a.left, b.left);
          const overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
          if (sideBySide && overlap > 0.5 * Math.min(a.height, b.height) && Math.abs(a.top - b.top) > tolerance) {
            const name = (el: Element) => el.tagName.toLowerCase() + [...el.classList].map((c) => '.' + c).join('');
            misaligned.push(`${name(parent)}: ${name(cards[i].el)} topo ${Math.round(a.top)} × ${name(cards[j].el)} topo ${Math.round(b.top)}`);
          }
        }
      }
    }
    const wideTables = [...document.querySelectorAll('.table-responsive')]
      .filter((t) => t.clientWidth > 0 && t.scrollWidth > t.clientWidth + 1)
      .map((t) => `${t.closest('app-data-table')?.querySelector('caption')?.textContent?.trim() || 'tabela'}: ${t.scrollWidth} px em ${t.clientWidth} px`);
    // rolagem interna fora do padrão: o cartão cresce com o conteúdo e mesmo assim mostra barra vertical
    const innerScroll: string[] = [];
    for (const el of document.querySelectorAll<HTMLElement>(`${root} *`)) {
      if (el.clientHeight === 0 || el.scrollHeight <= el.clientHeight) continue;
      // campos de formulário rolam por natureza (texto maior que as linhas visíveis)
      if (el.matches('textarea, select')) continue;
      const cs = getComputedStyle(el);
      if (cs.overflowY !== 'auto' && cs.overflowY !== 'scroll') continue;
      const extra = el.scrollHeight - el.clientHeight;
      // `overflow-y: auto` sozinho já computa `overflow-x: auto` (CSS Overflow 3): "dois eixos" só quando o bloco rola de
      // fato na horizontal; área de altura fixa (flex ou `height`) que só rola na vertical é legítima (ux.md §23)
      const rollsX = (cs.overflowX === 'auto' || cs.overflowX === 'scroll') && el.scrollWidth > el.clientWidth + 1;
      const both = rollsX && cs.maxHeight === 'none';
      if (extra <= 4 || both) {
        const name = el.tagName.toLowerCase() + [...el.classList].slice(0, 3).map((c) => '.' + c).join('');
        innerScroll.push(`${name}: ${el.scrollHeight} px em ${el.clientHeight} px${extra <= 4 ? ' (barra que não rola)' : ' (rola nos dois eixos sem altura máxima)'}`);
      }
    }
    return { scrollHeight: doc.scrollHeight, scrollWidth: doc.scrollWidth, innerHeight, innerWidth, misaligned, wideTables, innerScroll };
  }, [ALIGN_TOLERANCE_PX, root] as const).catch(() => null);
}

async function auditProfile(browser: Browser, spec: string): Promise<ProfileResult> {
  const [tenant, user] = spec.split('/');
  const result: ProfileResult = { profile: spec, pages: [], problems: [], failure: null };
  const context = await browser.newContext({ viewport: { width: WIDTH, height: HEIGHT }, locale: 'pt-BR' });
  const page = await context.newPage();
  let where = 'login';
  let authorization: string | null = null;
  const seen = new Set<string>();
  const report = (type: ProblemType, detail: string): void => {
    const key = `${type}|${where}|${detail}`;
    if (seen.has(key)) return;
    seen.add(key);
    result.problems.push({ profile: spec, where, type, detail });
  };

  page.on('request', (r) => {
    const header = r.headers()['authorization'];
    if (header && new URL(r.url()).pathname.startsWith('/api/')) authorization = header;
  });
  page.on('response', (r) => {
    const url = new URL(r.url());
    if (url.origin === BASE && url.pathname.startsWith('/api/') && r.status() === 403) {
      report('403', `${r.request().method()} ${normalizePath(url.pathname)}`);
    }
  });
  page.on('pageerror', (e) => report('erro-js', String(e.message ?? e).split('\n')[0].slice(0, 200)));

  const dismissTour = async (): Promise<void> => {
    for (const name of ['Pular tour', 'Agora não']) {
      const button = page.getByRole('button', { name, exact: true });
      if (await button.count()) await button.first().click({ timeout: 1000 }).catch(() => undefined);
    }
  };

  /** Espera a página assentar e aplica as verificações da página atual. */
  const settle = async (): Promise<void> => {
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => undefined);
    await page.waitForTimeout(700);
    await dismissTour();
    for (const text of await page.locator('ngb-toast').allInnerTexts().catch(() => [] as string[])) {
      if (/acesso negado/i.test(text)) report('acesso-negado', text.replace(/\s+/g, ' ').slice(0, 160));
    }
    await page.locator('ngb-toast .btn-close').evaluateAll((els) => els.forEach((e) => (e as HTMLElement).click()))
      .catch(() => undefined);
    const heading = await page.locator('main h1').first().innerText({ timeout: 1000 }).catch(() => '');
    if (/acesso negado/i.test(heading)) report('acesso-negado', 'página "Acesso negado" da rota');

    const layout = await layoutOf(page, 'main.app-content');
    if (layout) {
      for (const m of layout.misaligned) report('cartoes', m);
      if (layout.scrollHeight > layout.innerHeight || layout.scrollWidth > layout.innerWidth) {
        report('rolagem', `documento ${layout.scrollWidth}×${layout.scrollHeight} na janela ${layout.innerWidth}×${layout.innerHeight}`);
      }
      for (const t of layout.wideTables) report('rolagem', `tabela com barra horizontal — ${t}`);
      for (const t of layout.innerScroll) report('rolagem-interna', t);
    }
  };

  const navigate = async (href: string): Promise<void> => {
    await page.evaluate((h) => { history.pushState({}, '', h); dispatchEvent(new PopStateEvent('popstate')); }, href);
    await settle();
  };

  const visitTabs = async (base: string): Promise<void> => {
    const tabs = await page.locator(TAB_SELECTOR).evaluateAll((els) => els.map((e, i) => ({
      i, text: (e as HTMLElement).innerText.trim(), disabled: e.hasAttribute('disabled') || e.classList.contains('disabled'),
    })));
    for (const tab of tabs.filter((t) => !t.disabled && t.text)) {
      where = `${base} › aba ${tab.text}`;
      await page.locator(TAB_SELECTOR).nth(tab.i).click({ timeout: 3000 }).catch(() => undefined);
      await settle();
      result.pages.push(where);
    }
  };

  try {
    await spacedLogin();
    await page.goto(`${BASE}/login`);
    await page.locator('input[formcontrolname="tenant"]').fill(tenant);
    await page.locator('input[formcontrolname="email"]').fill(`${user}@${tenant}.exemplo.test`);
    await page.locator('input[type="password"]').fill(PASSWORD);
    for (let attempt = 0; ; attempt++) {
      await page.locator('button[type="submit"]').click();
      try {
        await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 10000 });
        break;
      } catch (e) {
        if (attempt >= 6) throw new Error(`login não concluído (${(e as Error).message.split('\n')[0]})`);
        await page.waitForTimeout(15000); // limite de logins por minuto: espera a janela andar
        await spacedLogin();
      }
    }
    where = 'inicio (/)';
    await settle();
    result.pages.push(where);

    const homeLinks = await page.locator('main a[href^="/"]').evaluateAll((as) =>
      [...new Set(as.map((a) => a.getAttribute('href') ?? '').filter((h) => h && h !== '/'))]);
    const menu = await page.locator('#menu-principal a.app-sidenav__link').evaluateAll((as) =>
      as.map((a) => ({ label: (a as HTMLElement).innerText.trim(), href: a.getAttribute('href') ?? '' })));
    await page.locator('button.app-search').click();
    await page.waitForTimeout(500);
    const paletteOptions = await page.locator('[role="dialog"] [role="option"]').allInnerTexts().catch(() => [] as string[]);
    await page.keyboard.press('Escape');

    const targets: Target[] = [];
    const routes = new Set<string>();
    for (const m of menu) {
      if (m.href && !routes.has(m.href)) { routes.add(m.href); targets.push({ kind: 'menu', label: m.label, href: m.href }); }
    }
    for (const h of homeLinks) {
      const path = h.split('?')[0];
      if (!routes.has(path)) { routes.add(path); targets.push({ kind: 'inicio', label: path, href: h }); }
    }

    for (const t of targets) {
      where = `${t.kind === 'menu' ? 'menu' : 'atalho'}: ${t.label} (${t.href.split('?')[0]})`;
      const base = where;
      try {
        const link = page.locator(`#menu-principal a.app-sidenav__link[href="${t.href}"]`);
        if (t.kind === 'menu' && await link.count()) {
          await link.first().click();
          await settle();
        } else {
          await navigate(t.href);
        }
        result.pages.push(where);
        await visitTabs(base);
        if (DETAIL && t.kind === 'menu') {
          await navigate(t.href);
          const prefix = t.href.replace(/\/$/, '') + '/';
          const detail = await page.locator('main a[href]').evaluateAll((as, pre) => as.map((a) => a.getAttribute('href') ?? '')
            .find((h) => h.startsWith(pre) && /\/[0-9a-f]{8}-[0-9a-f-]{20,}/i.test(h)), prefix);
          if (detail) {
            where = `detalhe: ${t.label} (${normalizePath(detail.split('?')[0])})`;
            await navigate(detail);
            result.pages.push(where);
            await visitTabs(where);
          }
        }
      } catch (e) {
        result.failure = `${where}: ${(e as Error).message.split('\n')[0]}`;
        break;
      }
    }

    // opções da busca que não são itens do menu (comandos de navegação das páginas)
    const menuLabels = new Set(menu.map((m) => m.label));
    const extras = paletteOptions.map((o) => o.split('\n')[0].trim()).filter((o) => o && !menuLabels.has(o))
      // só navegação: sair, tema, densidade e tour mudam a sessão ou as preferências
      .filter((o) => !/^(Sair|Usar tema|Seguir o tema|Densidade|Fazer o tour)/.test(o));
    for (const label of extras) {
      if (result.failure) break;
      where = `busca: ${label}`;
      await navigate('/');
      await page.locator('button.app-search').click().catch(() => undefined);
      await page.keyboard.type(label);
      await page.keyboard.press('Enter');
      await settle();
      result.pages.push(where);
    }
  } catch (e) {
    result.failure = `${where}: ${(e as Error).message.split('\n')[0]}`;
  } finally {
    // encerra a sessão (limite de sessões simultâneas por usuário)
    if (authorization) {
      await context.request.post(`${BASE}/api/v1/me/logout`, { headers: { Authorization: authorization } })
        .catch(() => undefined);
    }
    await context.close();
  }
  return result;
}

/**
 * Perfil da pessoa do credor: entrada em /credor/<assessoria>/entrar (senha + código do autenticador) e as telas do
 * portal. Só navega: nenhum botão de ação é acionado; abas (situação das contestações, atividade do time) são links.
 */
async function auditCreditorProfile(browser: Browser, spec: string): Promise<ProfileResult> {
  const [tenant, person] = spec.split('/');
  const result: ProfileResult = { profile: spec, pages: [], problems: [], failure: null };
  const hex = CREDITOR_KEYS.get(spec);
  if (!hex) {
    result.failure = 'pessoa do credor sem segredo no cofre do sandbox (a carga terminou? reset.sh recria)';
    return result;
  }
  const context = await browser.newContext({ viewport: { width: WIDTH, height: HEIGHT }, locale: 'pt-BR' });
  const page = await context.newPage();
  const base = `/credor/${tenant}`;
  let where = 'entrada do portal do credor';
  let authorization: string | null = null;
  let throttledUntil = 0;
  const seen = new Set<string>();
  const report = (type: ProblemType, detail: string): void => {
    const key = `${type}|${where}|${detail}`;
    if (seen.has(key)) return;
    seen.add(key);
    result.problems.push({ profile: spec, where, type, detail });
  };
  page.on('request', (r) => {
    const header = r.headers()['authorization'];
    if (header && new URL(r.url()).pathname.startsWith('/api/creditor/v1/')) authorization = header;
  });
  page.on('response', (r) => {
    const url = new URL(r.url());
    if (url.origin !== BASE || !url.pathname.startsWith('/api/')) return;
    if (r.status() === 403) report('403', `${r.request().method()} ${normalizePath(url.pathname)}`);
    if (r.status() === 429 && url.pathname.startsWith('/api/creditor/v1/auth/')) {
      const retry = Number(r.headers()['retry-after'] ?? '60');
      throttledUntil = Date.now() + (Number.isFinite(retry) ? retry : 60) * 1000 + 1000;
    }
  });
  page.on('pageerror', (e) => report('erro-js', String(e.message ?? e).split('\n')[0].slice(0, 200)));

  const settle = async (): Promise<void> => {
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => undefined);
    await page.waitForTimeout(700);
    for (const text of await page.locator('ngb-toast').allInnerTexts().catch(() => [] as string[])) {
      if (/acesso negado/i.test(text)) report('acesso-negado', text.replace(/\s+/g, ' ').slice(0, 160));
    }
    const layout = await layoutOf(page, 'main.cr__main');
    if (layout) {
      for (const m of layout.misaligned) report('cartoes', m);
      // o portal do credor rola o documento por desenho (moldura sem área de conteúdo fixa): só a horizontal conta
      if (layout.scrollWidth > layout.innerWidth) {
        report('rolagem', `documento ${layout.scrollWidth}×${layout.scrollHeight} na janela ${layout.innerWidth}×${layout.innerHeight}`);
      }
      for (const t of layout.wideTables) report('rolagem', `tabela com barra horizontal — ${t}`);
      for (const t of layout.innerScroll) report('rolagem-interna', t);
    }
  };
  const navigate = async (href: string): Promise<void> => {
    await page.evaluate((h) => { history.pushState({}, '', h); dispatchEvent(new PopStateEvent('popstate')); }, href);
    await settle();
  };
  const visitTabs = async (at: string): Promise<void> => {
    const tabs = await page.locator(TAB_SELECTOR).evaluateAll((els) => els.map((e, i) => ({
      i, text: (e as HTMLElement).innerText.trim(), tag: e.tagName,
      disabled: e.hasAttribute('disabled') || e.classList.contains('disabled'),
    })));
    for (const tab of tabs.filter((t) => !t.disabled && t.text)) {
      where = `${at} › aba ${tab.text}`;
      await page.locator(TAB_SELECTOR).nth(tab.i).click({ timeout: 3000 }).catch(() => undefined);
      await settle();
      result.pages.push(where);
    }
  };

  /** Senha e, num passo novo, o código; código recusado nunca é repetido (uma nova tentativa, com outro passo). */
  const login = async (): Promise<void> => {
    let lastStep = 0;
    for (let attempt = 0; attempt < 2; attempt++) {
      if (throttledUntil > Date.now()) await page.waitForTimeout(throttledUntil - Date.now());
      await spacedLogin();
      await page.goto(`${BASE}${base}/entrar`);
      await page.locator('input[formcontrolname="email"]').fill(`${person}@credor.exemplo.test`);
      await page.locator('input[type="password"]').fill(PASSWORD);
      await page.locator('button[type="submit"]').click();
      const code = page.locator('input[formcontrolname="code"]');
      const reached = await code.waitFor({ timeout: 15000 }).then(() => true, () => false);
      if (!reached) continue; // senha recusada ou 429: nenhum código foi gasto
      lastStep = await freshStep(lastStep);
      await code.fill(totp(hex, lastStep));
      await page.locator('button[type="submit"]').click();
      const ok = await page.waitForURL((u) => u.pathname.startsWith(`${base}/inicio`), { timeout: 15000 })
        .then(() => true, () => false);
      if (ok) return;
    }
    throw new Error('entrada no portal do credor não concluída (senha ou código recusados)');
  };

  try {
    await login();
    where = 'portal: Início';
    await settle();
    result.pages.push(where);
    const sections: Array<[string, string, string | null]> = [
      ['Prestação de contas', `${base}/prestacao-de-contas`, `${base}/prestacao-de-contas/`],
      ['Contestações', `${base}/contestacoes`, `${base}/contestacoes/`],
      ['Pagamentos informados', `${base}/pagamentos`, null],
      ['Minha atividade', `${base}/atividade`, null],
    ];
    for (const [label, href, detailPrefix] of sections) {
      where = `portal: ${label}`;
      await navigate(href);
      if (!page.url().includes(href)) continue; // sem a permissão, o guarda volta ao Início (o menu esconde)
      result.pages.push(where);
      await visitTabs(where);
      if (DETAIL && detailPrefix) {
        await navigate(href);
        const detail = await page.locator('main a[href]').evaluateAll((as, pre) => as.map((a) => a.getAttribute('href') ?? '')
          .find((h) => h.startsWith(pre) && /\/[0-9a-f]{8}-[0-9a-f-]{20,}/i.test(h)), detailPrefix);
        if (detail) {
          where = `portal: ficha de ${label} (${normalizePath(detail.split('?')[0])})`;
          await navigate(detail);
          result.pages.push(where);
          await visitTabs(where);
        }
      }
    }
  } catch (e) {
    result.failure = `${where}: ${(e as Error).message.split('\n')[0]}`;
  } finally {
    // encerra a sessão pela API (limite de 3 sessões por pessoa), sem acionar o "Sair" do menu
    if (authorization) {
      await context.request.post(`${BASE}/api/creditor/v1/session/logout`, { headers: { Authorization: authorization } })
        .catch(() => undefined);
    }
    await context.close();
  }
  return result;
}

function markdown(results: ProfileResult[]): string {
  const lines: string[] = [
    '# Auditoria da interface do sandbox',
    '',
    `Gerado em ${new Date().toISOString()} · ${BASE} · janela ${WIDTH}×${HEIGHT} · detalhe ${DETAIL ? 'sim' : 'não'}`,
    '',
    '| Perfil | Páginas e abas | 403 | Acesso negado | Cartões desalinhados | Documento rola | Rolagem interna | Erro JS | Falha da auditoria |',
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |',
  ];
  const count = (r: ProfileResult, t: ProblemType) => r.problems.filter((p) => p.type === t).length;
  for (const r of results) {
    lines.push(`| ${r.profile} | ${r.pages.length} | ${count(r, '403')} | ${count(r, 'acesso-negado')} | ${count(r, 'cartoes')} | `
      + `${count(r, 'rolagem')} | ${count(r, 'rolagem-interna')} | ${count(r, 'erro-js')} | ${r.failure ?? ''} |`);
  }
  const problems = results.flatMap((r) => r.problems);
  lines.push('', '## Problemas (perfil × página)', '');
  if (!problems.length) {
    lines.push('Nenhum.');
  } else {
    lines.push('| Perfil | Página | Tipo | Detalhe |', '| --- | --- | --- | --- |');
    for (const p of problems) lines.push(`| ${p.profile} | ${p.where.replace(/\|/g, '\\|')} | ${p.type} | ${p.detail.replace(/\|/g, '\\|')} |`);
  }
  lines.push('', '## Páginas visitadas', '');
  for (const r of results) {
    lines.push(`<details><summary>${r.profile} (${r.pages.length})</summary>`, '');
    for (const w of r.pages) lines.push(`- ${w}`);
    lines.push('', '</details>', '');
  }
  return lines.join('\n') + '\n';
}

async function main(): Promise<number> {
  const profiles = PROFILES.length ? PROFILES : DEFAULT_PROFILES.concat(DEFAULT_CREDITOR_PROFILES);
  for (const p of profiles) {
    if (!/^[a-z0-9-]+\/[a-z0-9-]+$/.test(p)) { console.error(`perfil inválido: ${p} (use assessoria/pessoa)`); return 2; }
  }
  mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const results: ProfileResult[] = [];
  const queue = [...profiles];
  const worker = async (): Promise<void> => {
    for (let spec = queue.shift(); spec; spec = queue.shift()) {
      const started = Date.now();
      const r = spec.split('/')[1].includes('-') ? await auditCreditorProfile(browser, spec) : await auditProfile(browser, spec);
      results.push(r);
      console.log(`${r.profile}: ${r.pages.length} páginas/abas, ${r.problems.length} problema(s)`
        + `${r.failure ? `, FALHA: ${r.failure}` : ''} (${Math.round((Date.now() - started) / 1000)} s)`);
    }
  };
  await Promise.all(Array.from({ length: Math.min(PARALLEL, profiles.length) }, worker));
  await browser.close();
  results.sort((a, b) => profiles.indexOf(a.profile) - profiles.indexOf(b.profile));

  writeFileSync(join(OUT, 'resultado.json'), JSON.stringify({ base: BASE, width: WIDTH, height: HEIGHT, detail: DETAIL, results }, null, 1));
  writeFileSync(join(OUT, 'relatorio.md'), markdown(results));
  const problems = results.flatMap((r) => r.problems);
  for (const p of problems) console.log(`PROBLEMA [${p.type}] ${p.profile} — ${p.where}: ${p.detail}`);
  const failures = results.filter((r) => r.failure);
  console.log(`Relatório: ${join(OUT, 'relatorio.md')} · ${results.reduce((n, r) => n + r.pages.length, 0)} páginas/abas, `
    + `${problems.length} problema(s), ${failures.length} perfil(is) com falha da auditoria`);
  if (failures.length) return 2;
  return problems.length ? 1 : 0;
}

main().then((code) => process.exit(code), (e: unknown) => { console.error(e); process.exit(2); });
