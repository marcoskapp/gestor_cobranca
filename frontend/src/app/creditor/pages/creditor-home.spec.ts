import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideBootstrapComponentDefaults } from '../../core/config/bootstrap-components.config';
import { provideBootstrapDefaults } from '../../core/config/bootstrap.config';
import { CreditorSession } from '../data/creditor-session';
import { CreditorAwaiting, CreditorAwaitingItem, CreditorMe, CreditorOverview } from '../data/creditor.models';
import { CreditorHome } from './creditor-home';

const OVERVIEW = 'CREDITOR_PORTAL_OVERVIEW';
const RESPONDER = [OVERVIEW, 'CREDITOR_DISPUTE_RESPOND', 'CREDITOR_STATEMENT_RESPOND', 'CREDITOR_PAYMENT_REPORT'];
const OVERVIEW_URL = '/api/creditor/v1/overview';
const AWAITING_URL = '/api/creditor/v1/awaiting';

function me(permissions: string[]): CreditorMe {
  return { userId: 'u1', name: 'Ana Souza', email: 'ana@banco.test', role: 'GESTOR', roleLabel: 'Gestão de recuperação', permissions,
    creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
}

function overview(overrides: Partial<CreditorOverview> = {}): CreditorOverview {
  return {
    period: 'THIS_MONTH', from: '2026-11-01', to: '2026-11-26', previousFrom: '2026-10-01', previousTo: '2026-10-26',
    generatedAt: '2026-11-26T13:00:00Z', dataUntil: '2026-11-26T12:45:00Z',
    recovery: { recovered: 11000, paidToCreditor: 1500, previousRecovered: 10000, previousPaidToCreditor: 0 },
    monthly: [
      { month: '2026-06', recovered: 8000, paidToCreditor: 0 },
      { month: '2026-07', recovered: 9000, paidToCreditor: 0 },
      { month: '2026-08', recovered: 12000, paidToCreditor: 500 },
      { month: '2026-09', recovered: 9500, paidToCreditor: 0 },
      { month: '2026-10', recovered: 10000, paidToCreditor: 0 },
      { month: '2026-11', recovered: 11000, paidToCreditor: 1500 },
    ],
    byPortfolio: [
      { portfolioId: 'p2', name: 'Cartões', recovered: 3000, others: false },
      { portfolioId: null, name: null, recovered: 1000, others: true },
      { portfolioId: 'p1', name: 'Veículos', recovered: 7000, others: false },
    ],
    balance: { amount: 250000, titles: 1234, referenceDate: '2026-11-26' },
    agreements: { onTrack: 164, active: 200, ratio: 0.82, alert: false, referenceDate: '2026-11-26' },
    transfer: { periodStart: '2026-11-01', asOf: '2026-11-26', expectedPeriodEnd: '2026-11-30', netTransfer: -150,
      direction: 'CREDITOR_TO_AGENCY', calculatedAt: '2026-11-26T12:00:00Z' },
    unavailable: [],
    ...overrides,
  };
}

function item(kind: CreditorAwaitingItem['kind'], id: string, overrides: Partial<CreditorAwaitingItem> = {}): CreditorAwaitingItem {
  return {
    kind, id, dueOn: null, overdue: false, dueSoon: false, youCanAct: true,
    dispute: kind === 'DISPUTE' ? { protocol: 'CT-' + id, type: 'ALREADY_PAID' } : null,
    statement: kind === 'STATEMENT' ? { sequenceNumber: 10, periodStart: '2026-10-01', periodEnd: '2026-10-31' } : null,
    payment: kind === 'PAYMENT_REJECTED' ? { paidOn: '2026-11-20', amount: 300, rejectionReason: 'DUPLICATE', decidedAt: '2026-11-22T12:00:00Z' } : null,
    ...overrides,
  };
}

/** Na ordem do servidor: vencidos primeiro, depois pelo prazo, depois os sem prazo. */
function awaiting(overrides: Partial<CreditorAwaiting> = {}): CreditorAwaiting {
  return {
    total: 4, dueSoon: 0, overdue: 1, counts: { disputes: 2, statements: 1, rejectedPayments: 1 },
    items: [
      item('DISPUTE', 'late', { dueOn: '2026-11-20', overdue: true }),
      item('STATEMENT', 's10', { dueOn: '2026-12-05' }),
      item('DISPUTE', 'later', { dueOn: '2026-12-10' }),
      item('PAYMENT_REJECTED', 'p1'),
    ],
    unavailable: [],
    ...overrides,
  };
}

/** Início do portal do credor: indicadores do `/overview`, pendências do `/awaiting` e estados por cartão. */
describe('início do portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideBootstrapDefaults(),
        provideRouter([{ path: 'credor/:slug', providers: [provideBootstrapComponentDefaults()],
          children: [{ path: 'inicio', component: CreditorHome }] }])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function login(permissions: string[]): void {
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: me(permissions) });
  }

  async function open(url = '/credor/aurora/inicio'): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create(url);
    harness.detectChanges();
    return { harness, root: harness.routeNativeElement! };
  }

  const fail = { title: 'Erro' };
  async function flush(harness: RouterTestingHarness, data: { overview?: CreditorOverview | 'error'; awaiting?: CreditorAwaiting | 'error' } = {},
    period = 'THIS_MONTH'): Promise<void> {
    const o = http.expectOne((r) => r.url === OVERVIEW_URL);
    expect(o.request.params.get('period')).toBe(period);
    if (data.overview === 'error') {
      o.flush(fail, { status: 500, statusText: 'Erro' });
    } else {
      o.flush(data.overview ?? overview());
    }
    const a = http.expectOne(AWAITING_URL);
    if (data.awaiting === 'error') {
      a.flush(fail, { status: 500, statusText: 'Erro' });
    } else {
      a.flush(data.awaiting ?? awaiting());
    }
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  const kpis = (root: HTMLElement) => [...root.querySelectorAll('app-kpi-card')];
  const value = (card: Element) => card.querySelector('.app-kpi__value')!.textContent!.replace(/\s+/g, ' ').trim();
  const items = (root: HTMLElement) => [...root.querySelectorAll('#ch-aguardando-lista > li')].map((li) => li.querySelector('a')!.textContent!.trim());

  it('sem a visão geral do portal: só a conta, sem nenhuma chamada', async () => {
    login([]);
    const { root } = await open();
    http.verify();
    expect(root.querySelector('h1')!.textContent).toContain('Olá, Ana');
    expect(root.textContent).toContain('Sua conta');
    expect(root.querySelector('app-kpi-card')).toBeNull();
    expect(root.textContent).not.toContain('Aguardando');
    expect(root.textContent).not.toContain('Ver minha atividade');
  });

  it('carregando: cada cartão mostra o próprio estado', async () => {
    login(RESPONDER);
    const { root } = await open();
    expect(kpis(root)[0].querySelector('.visually-hidden')!.textContent).toContain('Carregando');
    expect(root.querySelector('[data-tour="creditor-home-awaiting"] app-skeleton')).not.toBeNull();
    http.match(() => true).forEach((r) => r.flush(r.request.url === OVERVIEW_URL ? overview() : awaiting()));
  });

  it('cartões na ordem, "Acordos em dia" primeiro e em destaque; repasse sem sinal negativo', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness);
    const cards = kpis(root);
    expect(cards.map((k) => k.getAttribute('aria-label'))).toEqual(
      ['Acordos em dia', 'Saldo em cobrança', 'Recuperado no período', 'Repasse estimado', 'Aguardando você']);
    expect(cards[0].classList).toContain('app-kpi--emphasis');
    expect(cards[0].classList).not.toContain('app-kpi--warning');
    expect(value(cards[0])).toBe('82%');
    expect(cards[0].textContent).toContain('164 de 200 acordos em vigor em 26/11/2026');
    expect(cards[1].textContent).toContain('1.234 títulos em cobrança em 26/11/2026');
    expect(cards[2].textContent).toContain('+10% sobre o período anterior');
    expect(cards[2].textContent).toContain('Pago direto a você: R$');
    expect(cards[3].textContent).toContain('Período em aberto desde 01/11/2026. Você deve pagar à assessoria; o valor final sai no fechamento');
    expect(cards[3].textContent).not.toContain('−');
    expect(value(cards[4])).toBe('4');
    expect(cards[4].textContent).toContain('1 com prazo vencido');
    expect(cards[4].classList).toContain('app-kpi--danger');
    expect(root.textContent).toContain('Dados até 26/11/2026');
  });

  it('acordos abaixo de 80%: alerta com o motivo escrito; sem acordo, texto e não 0%', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { overview: overview({ agreements: { onTrack: 159, active: 200, ratio: 0.7999, alert: true, referenceDate: '2026-11-26' } }) });
    const card = kpis(root)[0];
    expect(card.classList).toContain('app-kpi--warning');
    expect(value(card)).toBe('79,9%');
    expect(card.textContent).toContain('Abaixo de 80%: 41 acordos têm parcela vencida e não paga');
  });

  it('sem acordo em vigor: "Nenhum acordo em vigor"', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { overview: overview({ agreements: { onTrack: 0, active: 0, ratio: null, alert: false, referenceDate: '2026-11-26' } }) });
    const card = kpis(root)[0];
    expect(value(card)).toBe('Nenhum acordo em vigor');
    expect(card.textContent).not.toContain('0%');
    expect(card.classList).not.toContain('app-kpi--warning');
  });

  it('pendências na ordem do servidor, com prazo e links para as fichas', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness);
    expect(root.querySelector('#ch-aguardando')!.textContent).toContain('Aguardando você (4)');
    expect(items(root)).toEqual([
      'Contestação · protocolo CT-late · Alegação de pagamento',
      'Demonstrativo nº 10 · 01/10 a 31/10',
      'Contestação · protocolo CT-later · Alegação de pagamento',
      'Pagamento informado de 20/11 recusado',
    ]);
    const list = root.querySelector('#ch-aguardando-lista')!;
    expect(list.textContent).toContain('Prazo vencido em 20/11');
    expect(list.textContent).toContain('Aceitar ou contestar até 05/12');
    expect(list.textContent).toContain('Responder até 10/12');
    expect(list.textContent).toContain('Confira e informe de novo');
    expect(list.textContent).toContain('Este pagamento já estava registrado.');
    expect(list.textContent).not.toContain('Quem responde');
    const hrefs = [...list.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(['/credor/aurora/contestacoes/late', '/credor/aurora/prestacao-de-contas/s10',
      '/credor/aurora/contestacoes/later', '/credor/aurora/pagamentos?pagamento=p1']);
  });

  it('itens que a pessoa não resolve (youCanAct=false): "Aguardando o seu time" e quem responde', async () => {
    login([OVERVIEW]);
    const { harness, root } = await open();
    const all = awaiting().items.map((i) => ({ ...i, youCanAct: false }));
    await flush(harness, { awaiting: awaiting({ items: all }) });
    expect(root.querySelector('#ch-aguardando')!.textContent).toContain('Aguardando o seu time (4)');
    expect(kpis(root)[4].getAttribute('aria-label')).toBe('Aguardando o seu time');
    const list = root.querySelector('#ch-aguardando-lista')!.textContent!;
    expect(list).toContain('Quem responde: Gestão de recuperação ou Jurídico.');
    expect(list).toContain('Quem responde: Financeiro.');
    expect(list).toContain('Quem informa: Gestão de recuperação ou Financeiro.');
  });

  it('vazio: "Nada depende de você agora."', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { awaiting: awaiting({ total: 0, overdue: 0, items: [], counts: { disputes: 0, statements: 0, rejectedPayments: 0 } }) });
    expect(root.textContent).toContain('Nada depende de você agora.');
    expect(value(kpis(root)[4])).toBe('0');
  });

  it('fonte de pendências fora do ar (unavailable): aviso parcial com "Tentar novamente", que relê', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { awaiting: awaiting({ unavailable: ['STATEMENTS'] }) });
    const card = root.querySelector('[data-tour="creditor-home-awaiting"]')!;
    expect(card.querySelector('[role="alert"]')!.textContent).toContain('Não foi possível carregar os demonstrativos agora');
    expect(items(root)).toHaveLength(4);
    [...card.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Tentar novamente'))!.click();
    harness.detectChanges();
    http.expectOne(AWAITING_URL).flush(awaiting());
    http.expectNone((r) => r.url === OVERVIEW_URL);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(card.querySelector('[role="alert"]')).toBeNull();
  });

  it('indicadores fora do ar (unavailable): um aviso só acima dos cartões; cartões e gráficos "Indisponível agora"', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { overview: overview({ unavailable: ['INDICATORS'], recovery: null, monthly: null, byPortfolio: null, balance: null,
      agreements: null }) });
    const cards = kpis(root);
    expect(cards[0].textContent).toContain('Indisponível agora');
    expect(cards[0].textContent).not.toContain('Tentar novamente');
    expect(cards[1].textContent).toContain('Indisponível agora');
    expect(cards[3].textContent).toContain('Você deve pagar à assessoria');
    expect(root.querySelector('app-column-chart')).toBeNull();
    const charts = root.querySelector('[data-tour="creditor-home-charts"]')!;
    expect(charts.textContent).toContain('Recuperado nos últimos 6 meses');
    expect(charts.textContent).toContain('Indisponível agora.');
    expect(charts.querySelector('[role="alert"], button')).toBeNull();
    const alerts = root.querySelectorAll('[role="alert"]');
    expect(alerts).toHaveLength(1);
    expect(alerts[0].textContent).toContain('Não foi possível carregar os indicadores e os gráficos agora.');
    const retry = [...root.querySelectorAll('button')].filter((b) => b.textContent?.includes('Tentar novamente'));
    expect(retry).toHaveLength(1);
    retry[0].click();
    harness.detectChanges();
    http.expectOne((r) => r.url === OVERVIEW_URL).flush(overview());
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(root.querySelectorAll('[role="alert"]')).toHaveLength(0);
    expect(value(kpis(root)[0])).toBe('82%');
  });

  it('repasse fora do ar (unavailable): só o cartão do repasse fica indisponível, com o aviso acima', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { overview: overview({ unavailable: ['TRANSFER'], transfer: null }) });
    const cards = kpis(root);
    expect(value(cards[0])).toBe('82%');
    expect(cards[3].textContent).toContain('Indisponível agora');
    expect(root.querySelector('[role="alert"]')!.textContent).toContain('Não foi possível carregar o repasse estimado agora.');
    expect(root.querySelector('app-column-chart')).not.toBeNull();
  });

  it('erro nas duas chamadas: um aviso do resumo e um das pendências, cada um com um "Tentar novamente" que relê só o seu', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness, { overview: 'error', awaiting: 'error' });
    expect(kpis(root)[0].textContent).toContain('Indisponível agora');
    expect(kpis(root).some((k) => k.querySelector('[role="alert"]')
      || [...k.querySelectorAll('button')].some((b) => b.textContent?.includes('Tentar novamente')))).toBe(false);
    expect(kpis(root)[4].textContent).toContain('Indisponível agora');
    expect(root.querySelectorAll('[role="alert"]')).toHaveLength(2);
    expect([...root.querySelectorAll('button')].filter((b) => b.textContent?.includes('Tentar novamente'))).toHaveLength(2);
    expect(root.querySelector('[data-tour="creditor-home-awaiting"]')!.textContent).toContain('Não foi possível carregar as pendências agora.');
    [...root.querySelectorAll<HTMLButtonElement>('[data-tour="creditor-home-awaiting"] button')].find((b) => b.textContent?.includes('Tentar novamente'))!.click();
    harness.detectChanges();
    http.expectOne(AWAITING_URL).flush(awaiting());
    http.expectNone((r) => r.url === OVERVIEW_URL);
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(items(root)).toHaveLength(4);
  });

  it('gráficos: série mensal de 6 meses e carteiras com "Outros" por último, com resumo escrito', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness);
    const monthly = root.querySelector('app-column-chart')!;
    expect(monthly.querySelector('figcaption, .cc__title')?.textContent ?? monthly.textContent).toContain('Recuperado nos últimos 6 meses');
    expect(monthly.querySelectorAll('.cc__col')).toHaveLength(6);
    const bars = root.querySelector('app-bar-chart')!;
    expect(bars.textContent).toContain('a maior parte veio de Veículos');
    const labels = [...bars.textContent!.matchAll(/(Veículos|Cartões|Outros)/g)].map((m) => m[1]);
    expect(labels.indexOf('Veículos')).toBeLessThan(labels.indexOf('Outros'));
    expect(labels.indexOf('Cartões')).toBeLessThan(labels.indexOf('Outros'));
  });

  it('período na URL: abre com ?periodo= e troca sem perder o resto', async () => {
    login(RESPONDER);
    const { harness, root } = await open('/credor/aurora/inicio?periodo=ultimos-3-meses');
    await flush(harness, { overview: overview({ period: 'LAST_3_MONTHS' }) }, 'LAST_3_MONTHS');
    const pressed = () => [...root.querySelectorAll('[data-tour="creditor-home-period"] button')]
      .filter((b) => b.getAttribute('aria-pressed') === 'true').map((b) => b.textContent!.trim());
    expect(pressed()).toEqual(['Últimos 3 meses']);
    [...root.querySelectorAll<HTMLButtonElement>('[data-tour="creditor-home-period"] button')].find((b) => b.textContent?.includes('Este ano'))!.click();
    harness.detectChanges();
    const req = http.expectOne((r) => r.url === OVERVIEW_URL);
    expect(req.request.params.get('period')).toBe('THIS_YEAR');
    req.flush(overview({ period: 'THIS_YEAR' }));
    // a troca grava ?periodo= (sem entrada no histórico); mudar só a consulta não relê as pendências
    for (let i = 0; i < 5; i++) {
      await new Promise((resolve) => setTimeout(resolve));
      harness.detectChanges();
      http.match((r) => r.url === AWAITING_URL).forEach((r) => r.flush(awaiting()));
    }
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toContain('periodo=este-ano');
    expect(pressed()).toEqual(['Este ano']);
  });

  it('até 5 pendências; "Ver todas" abre o resto no próprio cartão', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    const many = Array.from({ length: 7 }, (_, i) => item('DISPUTE', 'd' + i, { dueOn: `2099-12-${String(10 + i)}` }));
    await flush(harness, { awaiting: awaiting({ total: 7, overdue: 0, items: many }) });
    expect(items(root)).toHaveLength(5);
    const more = [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Ver todas (7)'))!;
    expect(more.getAttribute('aria-expanded')).toBe('false');
    more.click();
    harness.detectChanges();
    expect(items(root)).toHaveLength(7);
  });

  it('"Ver todas" conta pelo total; o rodapé leva só às listas com itens', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    const many = Array.from({ length: 6 }, (_, i) => item('DISPUTE', 'd' + i, { dueOn: `2099-12-${String(10 + i)}` }));
    await flush(harness, { awaiting: awaiting({ total: 60, overdue: 0, items: many,
      counts: { disputes: 55, statements: 5, rejectedPayments: 0 } }) });
    const more = [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Ver todas'))!;
    expect(more.textContent).toContain('Ver todas (60)');
    const foot = root.querySelector('.ch-foot')!;
    const links = [...foot.querySelectorAll('a')].map((a) => [a.textContent!.trim(), a.getAttribute('href')]);
    expect(links).toEqual([['Contestações (55)', '/credor/aurora/contestacoes'],
      ['Prestação de contas (5)', '/credor/aurora/prestacao-de-contas']]);
    more.click();
    harness.detectChanges();
    expect(foot.textContent).toContain('Mostrando os 6 mais urgentes de 60.');
  });

  it('período no celular: a mesma escolha numa lista', async () => {
    login(RESPONDER);
    const { harness, root } = await open();
    await flush(harness);
    const select = root.querySelector<HTMLSelectElement>('#ch-periodo')!;
    expect(root.querySelector('label[for="ch-periodo"]')!.textContent).toContain('Período dos indicadores');
    expect([...select.options].map((o) => o.textContent!.trim())).toEqual(['Este mês', 'Mês passado', 'Últimos 3 meses', 'Este ano']);
    select.value = 'mes-passado';
    select.dispatchEvent(new Event('change'));
    harness.detectChanges();
    const req = http.expectOne((r) => r.url === OVERVIEW_URL);
    expect(req.request.params.get('period')).toBe('LAST_MONTH');
    req.flush(overview({ period: 'LAST_MONTH' }));
    for (let i = 0; i < 3; i++) {
      await new Promise((resolve) => setTimeout(resolve));
      harness.detectChanges();
      http.match((r) => r.url === AWAITING_URL).forEach((r) => r.flush(awaiting()));
    }
  });
});
