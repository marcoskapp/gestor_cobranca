import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, setup, text } from '../../../../testing/component-harness';
import { ReportedTitleItem, ReportedPaymentItem } from './creditor-reported.api';
import { CreditorReportedPage } from './creditor-reported-page';
import { batchText, channelText, defaultPeriod, periodProblem, periodSince } from './creditor-reported.rules';

function item(overrides: Partial<ReportedPaymentItem> = {}): ReportedPaymentItem {
  return {
    paymentId: 'pg-1',
    paidAt: '2026-11-20T15:00:00Z',
    informedAt: '2026-11-20T16:00:00Z',
    creditorId: 'c-1',
    debtorId: 'd-1',
    debtorName: 'Maria Fictícia',
    debtorAnonymized: false,
    agreementId: null,
    titleIds: ['t-1'],
    titleCount: 1,
    amount: 120,
    origin: 'CREDITOR_DIRECT',
    channel: 'INTEGRATION',
    token: { id: 'tok-1', name: 'ERP do Banco A', prefix: 'gc_ab12', revoked: true, found: true, state: 'REVOKED' },
    status: 'CONFIRMED',
    evidenceAvailable: true,
    evidenceUnverified: true,
    ...overrides,
  };
}

function importItem(overrides: Partial<ReportedTitleItem> = {}): ReportedTitleItem {
  return {
    titleId: 't-9',
    titleReference: 'CTR-9/1',
    creditorId: 'c-1',
    portfolioId: 'p-1',
    debtorId: 'd-1',
    debtorName: 'Maria Fictícia',
    debtorAnonymized: false,
    situation: 'SETTLED_BY_IMPORT',
    importBatchId: 'b-1',
    occurredAt: '2026-11-21T10:00:00Z',
    amount: 300,
    titleStatus: 'SETTLED',
    ...overrides,
  };
}

const pageOf = <T>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

describe('CreditorReportedPage (baixas informadas pelo credor)', () => {
  let paymentsParams: URLSearchParams | null;

  async function open(url: string, permissions: string[], payments: ReportedPaymentItem[] = [item()], imports: ReportedTitleItem[] = [importItem()]) {
    const { http } = setup(CreditorReportedPage, permissions);
    await TestBed.inject(Router).navigateByUrl(url);
    paymentsParams = null;
    const fixture = await render(CreditorReportedPage, http, (u, req) => {
      if (u.endsWith('/payment/creditor-reported')) {
        paymentsParams = new URLSearchParams(req.request.params.toString());
        return { items: pageOf(payments), totals: { count: payments.length, amount: 120, effectiveAmount: 120 } };
      }
      if (u.endsWith('/titles/creditor-reported')) {
        return { items: pageOf(imports), totals: { count: imports.length, amount: 300 } };
      }
      if (u.endsWith('/ingestion/batches/labels')) {
        return [{ id: 'b-1', createdAt: '2026-11-21T09:00:00Z', layoutCode: 'PADRAO', layoutVersion: 2, source: 'CSV' }];
      }
      return u.endsWith('/names') ? [] : undefined;
    });
    return { fixture, http };
  }

  it('aba de pagamentos: período padrão no pedido, canal com o token, aviso do comprovante e links por permissão', async () => {
    const { fixture } = await open('/pagamentos/baixas-do-credor', ['PAYMENT_VIEW', 'DEBTOR_VIEW', 'PUBLIC_API_TOKEN_VIEW']);
    const el = fixture.nativeElement as HTMLElement;
    const t = text(fixture).replace(/\s+/g, ' ');
    expect(paymentsParams?.get('from')).toBe(defaultPeriod().from);
    expect(paymentsParams?.get('to')).toBe(defaultPeriod().to);
    expect(t).toContain('Integração do credor · ERP do Banco A (gc_ab12), acesso revogado');
    expect(t).toContain('Comprovante sem verificação de vírus');
    expect(el.querySelector('[aria-label="Sobre: Comprovante sem verificação de vírus"]')).not.toBeNull();
    expect(el.querySelector('a[href="/pagamentos/pg-1"]')?.getAttribute('aria-label')).toContain('Abrir pagamento de');
    expect(t).toContain('1 pagamento');
    expect(el.querySelector('a[href="/devedores/d-1"]')?.textContent).toContain('Maria Fictícia');
    expect(el.querySelector('a[href="/titulos/t-1"]')).not.toBeNull();
    expect(el.querySelector('a[href="/integracoes/tokens/tok-1"]')).not.toBeNull();
    expect(el.querySelector('a[href="/pagamentos/pg-1"]')).not.toBeNull();
  });

  it('sem DEBTOR_VIEW, CASE_VIEW e PUBLIC_API_TOKEN_VIEW: sem coluna de devedor, sem links de título e de token', async () => {
    const { fixture } = await open('/pagamentos/baixas-do-credor?token=11111111-1111-4111-8111-111111111111', ['PAYMENT_VIEW'],
      [item({ debtorName: null, titleIds: [], token: null })]);
    const el = fixture.nativeElement as HTMLElement;
    const t = text(fixture).replace(/\s+/g, ' ');
    expect(t).not.toContain('Devedor');
    expect(t).toContain('1 título');
    expect(t).toContain('Integração do credor');
    expect(el.querySelector('a[href^="/titulos"]')).toBeNull();
    expect(el.querySelector('a[href^="/integracoes/tokens"]')).toBeNull();
    // o filtro de token da URL é ignorado sem a permissão
    expect(paymentsParams?.has('tokenId')).toBe(false);
  });

  it('período acima de 366 dias não consulta o servidor e explica', async () => {
    const { fixture } = await open('/pagamentos/baixas-do-credor?de=2024-01-01&ate=2026-01-10', ['PAYMENT_VIEW']);
    expect(paymentsParams).toBeNull();
    expect(text(fixture)).toContain('O período pode ter até 366 dias');
  });

  it('aba da carga pela URL: situação, rótulo e link do lote só com IMPORT_VIEW, anonimizado com link do título', async () => {
    const { fixture } = await open('/pagamentos/baixas-do-credor?aba=carga&lote=b-1', ['PAYMENT_VIEW', 'DEBTOR_VIEW', 'IMPORT_VIEW'],
      [], [importItem(), importItem({ titleId: 't-8', titleReference: null, debtorName: 'Titular anonimizado', debtorAnonymized: true, situation: 'REPORTED_PAID' })]);
    const el = fixture.nativeElement as HTMLElement;
    const t = text(fixture).replace(/\s+/g, ' ');
    expect(t).toContain('Saldo zerado pela carga do credor');
    expect(t).toContain('Credor informou pagamento');
    expect(t).toContain('Zerado ou suspenso em');
    // aviso do filtro com o rótulo do lote (UX-07)
    expect(el.querySelector('[data-testid="batch-filter"]')?.textContent).toContain('Só os títulos da carga de');
    // titular anonimizado: sem a referência, mas com o link para o título (UX-04)
    expect(el.querySelector('a[href="/titulos/t-8"]')?.textContent).toContain('Abrir título');
    expect(el.querySelector('form[data-tour="filters"]')).not.toBeNull();
    expect(el.querySelector('a[href="/importacao/b-1"]')?.textContent).toContain('layout PADRAO v2');
    expect(el.querySelector('a[href="/titulos/t-9"]')?.textContent).toContain('CTR-9/1');
  });

  it('aba da carga sem IMPORT_VIEW: nem consulta rótulos nem mostra link do lote', async () => {
    const { fixture, http } = await open('/pagamentos/baixas-do-credor?aba=carga', ['PAYMENT_VIEW'], [],
      [importItem({ titleReference: null, debtorName: null, importBatchId: null })]);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('a[href^="/importacao"]')).toBeNull();
    expect(el.querySelector('a[href^="/titulos"]')).toBeNull();
    expect(text(fixture)).toContain('Carga do credor');
    http.expectNone((r) => r.url.endsWith('/ingestion/batches/labels'));
  });
});

describe('regras da lista de baixas do credor', () => {
  it('período obrigatório, ordenado e de até 366 dias', () => {
    expect(periodProblem(null, '2026-01-01')).toContain('Informe o período');
    expect(periodProblem('2026-02-01', '2026-01-01')).toContain('anterior');
    expect(periodProblem('2025-01-01', '2026-01-02')).toBeNull();
    expect(periodProblem('2025-01-01', '2026-01-03')).toContain('366 dias');
  });

  it('canal sem token (perfil sem permissão) e integração não encontrada', () => {
    expect(channelText({ channel: 'CREDITOR_PORTAL', token: null })).toBe('Portal do credor');
    expect(channelText({ channel: 'INTEGRATION', token: { id: 'x', name: null, prefix: null, revoked: false, found: false, state: 'NOT_FOUND' } }))
      .toBe('Integração do credor · integração não identificada');
  });

  it('lote: nunca o nome do arquivo; sem rótulo visível, texto genérico', () => {
    expect(batchText(undefined, () => '')).toBe('Carga do credor');
    expect(batchText({ id: 'b', createdAt: '2026-11-21T09:00:00Z', layoutCode: 'PADRAO', layoutVersion: 2, source: 'CSV' }, () => '21/11/2026'))
      .toBe('Carga de 21/11/2026 · layout PADRAO v2');
  });

  it('atalho de outra tela: período do registro até hoje, limitado a 366 dias', () => {
    const today = new Date(2026, 10, 26);
    expect(periodSince('2026-11-01T12:00:00Z', today)).toEqual({ de: '2026-11-01', ate: '2026-11-26' });
    const old = periodSince('2020-01-01T12:00:00Z', today);
    expect(old.ate).toBe('2026-11-26');
    expect(periodProblem(old.de, old.ate)).toBeNull();
    expect(periodProblem(old.de, old.ate) === null && old.de > '2025-11-01').toBe(true);
  });
});
