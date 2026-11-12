import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { flushAll, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { CHANNEL_UNITS } from '../data/costing.labels';
import { ChannelInvoice, CostBand, ItemEstimate, UserCostBand } from '../data/costing.models';
import { InvoiceDialog, OverheadDialog, TariffDialog } from './cost-dialogs';
import { CostSettings } from './cost-settings';

const USER_1 = '00000000-0000-0000-0000-0000000000e1';
const USER_2 = '00000000-0000-0000-0000-0000000000e2';

const bands: CostBand[] = [
  { id: 'b-1', name: 'Negociador júnior', hourlyCost: 38, validFrom: '2026-01-01', validTo: null },
  { id: 'b-0', name: 'Negociador júnior', hourlyCost: 35, validFrom: '2025-01-01', validTo: '2025-12-31' },
  { id: 'b-2', name: 'Supervisor', hourlyCost: 62.5, validFrom: '2026-01-01', validTo: null },
];
const userBands: UserCostBand[] = [
  { userId: USER_1, bandName: 'Negociador júnior', validFrom: '2026-02-01', validTo: null },
  { userId: USER_2, bandName: null, validFrom: null, validTo: null },
];

const telephonyInvoice: ChannelInvoice = {
  id: 'i-1',
  month: '2026-10',
  item: 'TELEPHONY',
  amount: 180,
  supplier: 'Operadora fictícia',
  note: null,
  estimated: 150,
  adjustment: 30,
};

function estimates(month: string): ItemEstimate[] {
  return [
    { month, item: 'TELEPHONY', estimated: 150, quantity: 1500, unit: 'MINUTES', invoiceId: month === '2026-10' ? 'i-1' : null, invoiced: month === '2026-10' ? 180 : null },
    { month, item: 'WHATSAPP', estimated: 40, quantity: 100, unit: 'UNITS', invoiceId: null, invoiced: null },
    { month, item: 'SMS', estimated: 0, quantity: 0, unit: 'UNITS', invoiceId: null, invoiced: null },
    { month, item: 'EMAIL', estimated: 0, quantity: 0, unit: 'UNITS', invoiceId: null, invoiced: null },
    { month, item: 'AI', estimated: 54, quantity: 10, unit: 'USD', invoiceId: null, invoiced: null },
  ];
}

describe('configuração de custos operacionais', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const allCreditors = signal(true);
  const query = new BehaviorSubject(convertToParamMap({}));

  beforeEach(() => {
    query.next(convertToParamMap({}));
    allCreditors.set(true);
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: () => fakeUser({ allCreditors: allCreditors() }),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: query.asObservable(), get snapshot() { return { queryParamMap: query.value }; } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const handlers = {
    '/api/v1/costing/bands': bands,
    '/api/v1/costing/user-bands': userBands,
    '/api/v1/costing/tariffs': [{ id: 't-1', channel: 'WHATSAPP_MARKETING', unitCost: 0.0625, validFrom: '2026-01-01', validTo: null }],
    '/api/v1/costing/fx-rates': [],
    '/api/v1/costing/overheads': [
      { id: 'o-1', month: '2026-10', description: 'Aluguel', amount: 1000, allocation: 'HOURS', creditorId: null, creditorName: null },
      { id: 'o-2', month: '2026-10', description: 'Licença do credor', amount: 50, allocation: 'CREDITOR', creditorId: 'c-1', creditorName: 'Credor A' },
    ],
    '/api/v1/costing/invoices/estimates': (req: { request: { params: { get(n: string): string | null } } }) => {
      expect(req.request.params.get('from')).toBe(req.request.params.get('to'));
      return estimates(req.request.params.get('from')!);
    },
    '/api/v1/costing/invoices': (req: { request: { params: { get(n: string): string | null } } }) =>
      req.request.params.get('from') === '2026-10' ? [telephonyInvoice] : [],
    '/api/v1/users/display-names': (req: { request: { params: { get(n: string): string | null } } }) =>
      (req.request.params.get('ids') ?? '').split(',').map((id) => ({ id, name: id === USER_1 ? 'Ana Souza' : 'Bruno Lima' })),
  };

  async function render(): Promise<ComponentFixture<CostSettings>> {
    const fixture = TestBed.createComponent(CostSettings);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, handlers);
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const text = (node: Element | null) => (node?.textContent ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ');
  const tabs = (f: ComponentFixture<unknown>) => [...el(f).querySelectorAll('ul.app-tabs > li:not(.app-tabs-more-item) .nav-link')].map((t) => text(t).trim()).filter(Boolean);

  it('FINANCEIRO/ADMIN (as duas permissões): todas as abas; faixas abre primeiro', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE', 'COST_RATE_MANAGE', 'CASE_VIEW']);
    const fixture = await render();
    expect(tabs(fixture)).toEqual(['Faixas de custo de pessoas', 'Tarifas de canal', 'Câmbio da IA', 'Custos fixos', 'Faturas']);
    expect(text(el(fixture))).toContain('Nova faixa');
  });

  it('só COST_MANAGE: sem a aba de faixas de pessoas (oculta, não negada)', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    const fixture = await render();
    expect(tabs(fixture)).toEqual(['Tarifas de canal', 'Câmbio da IA', 'Custos fixos', 'Faturas']);
    expect(text(el(fixture))).toContain('WhatsApp — marketing');
    expect(text(el(fixture))).toContain('R$ 0,0625');
    expect(text(el(fixture))).toContain('Nova tarifa');
  });

  it('só COST_RATE_MANAGE (sem ver a margem): só a aba de faixas', async () => {
    permissions.set(['COST_RATE_MANAGE', 'CASE_VIEW']);
    const fixture = await render();
    expect(tabs(fixture)).toEqual(['Faixas de custo de pessoas']);
  });

  it('pessoas aparecem só com o nome da faixa, nunca com o valor-hora; quem está sem faixa vem primeiro', async () => {
    permissions.set(['COST_RATE_MANAGE', 'CASE_VIEW']);
    const fixture = await render();
    const tables = el(fixture).querySelectorAll('table');
    expect(tables.length).toBe(2);
    const bandsTable = text(tables[0]);
    expect(bandsTable).toContain('R$ 38,00 por hora');
    expect(bandsTable).toContain('R$ 62,50 por hora');
    expect(bandsTable).toContain('de 01/01/2025 a 31/12/2025');
    const people = tables[1];
    expect(text(people)).toContain('Ana Souza');
    expect(text(people)).toContain('Negociador júnior');
    expect(text(people)).not.toContain('R$');
    expect(text(people)).not.toMatch(/\d+,\d{2}/);
    const rows = [...people.querySelectorAll('tbody tr')].map((r) => text(r));
    expect(rows[0]).toContain('Bruno Lima');
    expect(rows[0]).toContain('Sem faixa');
    // sem USER_VIEW não há seletor de usuários: a troca é pela lista
    expect(text(el(fixture))).not.toContain('Atribuir faixa');
  });

  it('com USER_VIEW, atribui faixa escolhendo a pessoa no seletor de usuários', async () => {
    permissions.set(['COST_RATE_MANAGE', 'USER_VIEW']);
    const fixture = await render();
    expect(text(el(fixture))).toContain('Atribuir faixa');
  });

  it('só leitura (COST_VIEW sem COST_MANAGE): sem botões de escrita nas abas de custos', async () => {
    permissions.set(['COST_VIEW', 'COST_RATE_MANAGE', 'CASE_VIEW']);
    query.next(convertToParamMap({ aba: 'tarifas' }));
    const fixture = await render();
    expect(text(el(fixture))).toContain('WhatsApp — marketing');
    expect(text(el(fixture))).not.toContain('Nova tarifa');
    expect(el(fixture).querySelector('button[aria-label^="Encerrar a tarifa"]')).toBeNull();
  });
  it('e-mail é cobrado por mensagem (como o servidor calcula), e a tarifa pode ser zero', async () => {
    expect(CHANNEL_UNITS.EMAIL).toBe('por mensagem');
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    const fixture = await render();
    expect(text(el(fixture))).toContain('SMS e e-mail por mensagem');
    expect(text(el(fixture))).not.toContain('por mil');
  });

  it('escopo restrito: abas pela permissão, sem escrita nos cadastros da assessoria; só o custo fixo direto ao credor', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE', 'COST_RATE_MANAGE', 'USER_VIEW']);
    allCreditors.set(false);
    const fixture = await render();
    expect(tabs(fixture)).toEqual(['Faixas de custo de pessoas', 'Tarifas de canal', 'Câmbio da IA', 'Custos fixos']);
    // faixas: sem nova faixa, sem nova vigência, sem atribuir nem trocar a faixa de alguém
    expect(text(el(fixture))).not.toContain('Nova faixa');
    expect(text(el(fixture))).not.toContain('Atribuir faixa');
    expect(el(fixture).querySelector('button[aria-label^="Trocar a faixa"]')).toBeNull();
    expect(el(fixture).querySelector('button[aria-label^="Nova vigência"]')).toBeNull();
    fixture.destroy();

    query.next(convertToParamMap({ aba: 'tarifas' }));
    const tariffs = await render();
    expect(text(el(tariffs))).not.toContain('Nova tarifa');
    expect(el(tariffs).querySelector('button[aria-label^="Encerrar a tarifa"]')).toBeNull();
    tariffs.destroy();

    query.next(convertToParamMap({ aba: 'fixos' }));
    const fixed = await render();
    // lança custo fixo (só direto a um credor) e exclui só o direto ao credor
    expect(text(el(fixed))).toContain('Novo custo fixo');
    expect(el(fixed).querySelector('button[aria-label="Excluir o custo fixo Licença do credor"]')).not.toBeNull();
    expect(el(fixed).querySelector('button[aria-label="Excluir o custo fixo Aluguel"]')).toBeNull();
  });

  it('todos os credores: troca a faixa de uma pessoa e exclui qualquer custo fixo', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE', 'COST_RATE_MANAGE']);
    const fixture = await render();
    expect(el(fixture).querySelector('button[aria-label^="Trocar a faixa"]')).not.toBeNull();
    fixture.destroy();
    query.next(convertToParamMap({ aba: 'fixos' }));
    const fixed = await render();
    expect(el(fixed).querySelector('button[aria-label="Excluir o custo fixo Aluguel"]')).not.toBeNull();
  });

  it('faturas: cada canal do mês com o uso, a estimativa, a fatura e a diferença; mês e atalho para a margem conciliada', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    query.next(convertToParamMap({ aba: 'faturas', mes: '2026-10-01' }));
    const fixture = await render();
    const table = el(fixture).querySelector('table')!;
    const rows = [...table.querySelectorAll('tbody tr')].map((r) => text(r));
    expect(rows.length).toBe(5);
    expect(rows[0]).toContain('Telefonia');
    expect(rows[0]).toContain('Operadora fictícia');
    expect(rows[0]).toContain('1.500 minutos');
    expect(rows[0]).toContain('R$ 150,00');
    expect(rows[0]).toContain('R$ 180,00');
    expect(rows[0]).toContain('+R$ 30,00');
    expect(rows[1]).toContain('WhatsApp');
    expect(rows[1]).toContain('100 conversas');
    expect(rows[1]).toContain('Sem fatura');
    expect(rows[2]).toContain('sem uso');
    expect(rows[4]).toContain('US$');
    // sem códigos internos na tela
    expect(text(table)).not.toMatch(/TELEPHONY|WHATSAPP|MINUTES|UNITS/);
    // escrita com COST_MANAGE: informar, corrigir e excluir
    expect(text(el(fixture))).toContain('Informar fatura');
    expect(el(fixture).querySelector('button[aria-label="Corrigir a fatura de Telefonia"]')).not.toBeNull();
    expect(el(fixture).querySelector('button[aria-label="Excluir a fatura de Telefonia"]')).not.toBeNull();
    expect(el(fixture).querySelector('button[aria-label="Informar a fatura de WhatsApp"]')).not.toBeNull();
    const link = el(fixture).querySelector<HTMLAnchorElement>('a[href*="/paineis/margem"]')!;
    expect(link.getAttribute('href')).toContain('base=conciliado');
    expect(link.getAttribute('href')).toContain('de=2026-10-01');
    expect(link.getAttribute('href')).toContain('ate=2026-10-31');
  });

  it('faturas só leitura (COST_VIEW): sem informar, corrigir nem excluir', async () => {
    permissions.set(['COST_VIEW', 'COST_RATE_MANAGE']);
    query.next(convertToParamMap({ aba: 'faturas', mes: '2026-10-01' }));
    const fixture = await render();
    expect(text(el(fixture).querySelector('table'))).toContain('R$ 180,00');
    expect(text(el(fixture))).not.toContain('Informar fatura');
    expect(el(fixture).querySelector('button[aria-label^="Corrigir a fatura"]')).toBeNull();
    expect(el(fixture).querySelector('button[aria-label^="Excluir a fatura"]')).toBeNull();
  });

  it('faturas com escopo restrito: a aba não existe (nem pela URL), pois a fatura é da assessoria', async () => {
    permissions.set(['COST_VIEW', 'COST_MANAGE']);
    allCreditors.set(false);
    query.next(convertToParamMap({ aba: 'faturas' }));
    const fixture = await render();
    expect(tabs(fixture)).not.toContain('Faturas');
    expect(text(el(fixture))).toContain('WhatsApp — marketing');
  });

  it('faixa sem valor-hora (quem não gerencia as faixas recebe só o nome): mostra o traço', async () => {
    permissions.set(['COST_RATE_MANAGE']);
    const fixture = TestBed.createComponent(CostSettings);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, { ...handlers, '/api/v1/costing/bands': [{ id: 'b-9', name: 'Supervisor', hourlyCost: null, validFrom: '2026-01-01', validTo: null }] });
    }
    fixture.detectChanges();
    const bandsTable = text(el(fixture).querySelector('table'));
    expect(bandsTable).toContain('Supervisor');
    expect(bandsTable).not.toContain('R$');
  });
});

describe('diálogos de custos', () => {
  const close = vi.fn();
  const text = (node: Element | null) => (node?.textContent ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ');

  function dialog<T>(component: Type<T>, data: unknown): ComponentFixture<T> {
    TestBed.configureTestingModule({
      imports: [component],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: data },
        { provide: DialogRef, useValue: { close } },
      ],
    });
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return fixture;
  }

  it('tarifa zero é aceita (e-mail desprezível) e vai ao servidor', () => {
    const fixture = dialog(TariffDialog, { channel: 'EMAIL' });
    const http = TestBed.inject(HttpTestingController);
    const form = (fixture.componentInstance as unknown as { form: { patchValue(v: object): void; valid: boolean } }).form;
    form.patchValue({ unitCost: 0 });
    fixture.detectChanges();
    expect(form.valid).toBe(true);
    expect(text(fixture.nativeElement as Element)).toContain('por mensagem');
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    const req = http.expectOne('/api/v1/costing/tariffs');
    expect(req.request.body).toEqual(expect.objectContaining({ channel: 'EMAIL', unitCost: 0 }));
    req.flush({});
    http.verify();
  });

  it('custo fixo com escopo restrito: só a regra "direto a um credor", com o credor obrigatório', () => {
    const fixture = dialog(OverheadDialog, { creditorOnly: true });
    const options = [...(fixture.nativeElement as HTMLElement).querySelectorAll('select[formcontrolname="allocation"] option')].map((o) => text(o).trim());
    expect(options).toEqual(['Direto a um credor']);
    const form = (fixture.componentInstance as unknown as { form: { controls: { creditor: { valid: boolean } } } }).form;
    expect(form.controls.creditor.valid).toBe(false);
  });

  it('fatura: mostra a estimativa do mês do canal e a diferença; envia o mês AAAA-MM', async () => {
    const fixture = dialog(InvoiceDialog, { month: '2026-10-01', item: 'TELEPHONY', invoice: null });
    const http = TestBed.inject(HttpTestingController);
    await tick();
    const req = http.expectOne((r) => r.url === '/api/v1/costing/invoices/estimates');
    expect(req.request.params.get('from')).toBe('2026-10');
    req.flush(estimates('2026-10').map((e) => ({ ...e, invoiceId: null, invoiced: null })));
    await tick();
    const form = (fixture.componentInstance as unknown as { form: { patchValue(v: object): void } }).form;
    form.patchValue({ amount: 120, supplier: '  Operadora  ' });
    fixture.detectChanges();
    const hint = text((fixture.nativeElement as HTMLElement).querySelector('[data-testid="invoice-estimate"]'));
    expect(hint).toContain('Estimativa do mês: R$ 150,00 (1.500 minutos)');
    expect(hint).toContain('Diferença de −R$ 30,00');
    // canal fixo ao informar a partir da linha
    const options = [...(fixture.nativeElement as HTMLElement).querySelectorAll('select[formcontrolname="item"] option')].map((o) => text(o).trim());
    expect(options).toEqual(['Telefonia']);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    const save = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/costing/invoices');
    expect(save.request.body).toEqual({ month: '2026-10', item: 'TELEPHONY', amount: 120, supplier: 'Operadora', note: null });
    save.flush({});
    http.verify();
  });

  it('fatura de canal sem uso no mês avisa que o valor fica sem credor; mês já faturado avisa que substitui', async () => {
    const fixture = dialog(InvoiceDialog, { month: '2026-10-01', item: null, invoice: null });
    const http = TestBed.inject(HttpTestingController);
    await tick();
    http.expectOne((r) => r.url === '/api/v1/costing/invoices/estimates').flush(estimates('2026-10'));
    await tick();
    const form = (fixture.componentInstance as unknown as { form: { patchValue(v: object): void } }).form;
    // telefonia já tem fatura no mês: salvar substitui
    fixture.detectChanges();
    expect(text(fixture.nativeElement as Element)).toContain('salvar substitui o valor');
    form.patchValue({ item: 'SMS', amount: 10 });
    fixture.detectChanges();
    expect(text((fixture.nativeElement as HTMLElement).querySelector('[data-testid="invoice-estimate"]'))).toContain('fica em "Sem credor"');
    // meses: só os já começados (o servidor recusa mês futuro)
    const months = (fixture.nativeElement as HTMLElement).querySelectorAll('select[formcontrolname="month"] option');
    expect(months.length).toBe(25);
    http.verify();
  });

  it('custo fixo com todos os credores: as quatro regras, horas por padrão', () => {
    const fixture = dialog(OverheadDialog, { creditorOnly: false });
    const options = [...(fixture.nativeElement as HTMLElement).querySelectorAll('select[formcontrolname="allocation"] option')];
    expect(options.length).toBe(4);
  });
});
