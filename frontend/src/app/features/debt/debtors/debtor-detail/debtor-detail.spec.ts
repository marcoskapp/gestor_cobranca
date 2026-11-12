import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthService } from '../../../../core/auth/auth.service';
import { URL_STATE_EMBEDDED } from '../../../../shared/data/url-state';
import { CaseView } from '../../../collection/data/collection.models';
import { ContactView, DebtorSummary, DebtorView, TitleView } from '../../data/debt.models';
import { DebtorDetail } from './debtor-detail';

const debtor: DebtorView = {
  id: 'd-1',
  personType: 'INDIVIDUAL',
  name: 'Ana Paula Ribeiro',
  maskedDocument: '***.982.247-**',
  birthDate: '1980-02-01',
  status: 'ACTIVE',
  notes: null,
  createdAt: '2026-01-10T12:00:00Z',
  anonymizedAt: null,
  tradeName: null,
  establishment: null,
};

const summary: DebtorSummary = {
  debtorId: 'd-1',
  name: debtor.name,
  maskedDocument: debtor.maskedDocument,
  personType: 'INDIVIDUAL',
  status: 'ACTIVE',
  contacts: [],
  creditors: [{ creditorId: 'c-1', openBalance: 3120.45, maxDaysOverdue: 135, titles: [] }],
  totalOpenBalance: 3120.45,
  maxDaysOverdue: 135,
  titleCount: 1,
  coObligations: [],
  collateral: [],
};

const title: TitleView = {
  id: 't-1',
  creditorId: 'c-1',
  portfolioId: 'p-1',
  debtorId: 'd-1',
  originContractId: 'k-1',
  externalId: 'CARTAO-4471',
  installmentNumber: null,
  dueDate: '2026-06-10',
  principalAmount: 2450.9,
  openBalance: 3120.45,
  paidTotal: 0,
  status: 'OPEN',
  statusReason: null,
  disputeBlocked: false,
  disputeId: null,
  lastPaymentAt: null,
  daysOverdue: 135,
  importBatchId: null,
};

const contact: ContactView = {
  id: 'ct-1',
  debtorId: 'd-1',
  type: 'PHONE',
  maskedValue: '(11) 9****-4321',
  label: 'MOBILE',
  whatsapp: true,
  source: 'IMPORT',
  status: 'VALID',
  priority: 1,
  lastVerifiedAt: null,
  invalidReason: null,
  maskedPersonName: null,
  personRole: null,
  decisionMaker: false,
  receipt: null,
};

const caseOf = (id: string, status: CaseView['status'], portfolioId = 'p-1'): CaseView =>
  ({ id, debtorId: 'd-1', creditorId: 'c-1', portfolioId, status }) as CaseView;

describe('DebtorDetail', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DebtorDetail],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /** Selo de atenção especial (compliance) no cabeçalho da ficha: sem marcador. */
  const noMarker = (debtorId: string) => ({ debtorId, vulnerable: false, categories: [], paymentCapacity: null, pendingSuggestion: null, markers: [], canRegister: false, canManage: false });

  afterEach(() => {
    // na troca de devedor, o selo do segundo devedor (o do primeiro já foi conferido em render())
    http.match('/api/v1/compliance/vulnerability/debtors/d-2').filter((r) => !r.cancelled).forEach((r) => r.flush(noMarker('d-2')));
    http.verify();
  });

  /** Responde às cargas da ficha e à resolução de nomes (credor/carteira). */
  async function render(cases?: CaseView[]): Promise<ComponentFixture<DebtorDetail>> {
    const fixture = TestBed.createComponent(DebtorDetail);
    fixture.componentRef.setInput('id', 'd-1');
    fixture.detectChanges();
    http.expectOne('/api/v1/debtors/d-1').flush(debtor);
    http.expectOne('/api/v1/debtors/d-1/summary').flush(summary);
    http.expectOne('/api/v1/debtors/d-1/titles').flush([title]);
    http.expectOne('/api/v1/debtors/d-1/contacts').flush([contact]);
    if (cases) {
      http.expectOne('/api/v1/collection/debtors/d-1/cases').flush(cases);
    }
    await settle(fixture);
    // exatamente uma consulta do selo por ficha
    http.expectOne('/api/v1/compliance/vulnerability/debtors/d-1').flush(noMarker('d-1'));
    fixture.detectChanges();
    return fixture;
  }

  async function settle(fixture: ComponentFixture<DebtorDetail>): Promise<void> {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await Promise.resolve();
      for (const req of http.match((r) => r.url.startsWith('/api/v1/creditors/'))) {
        req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte', document: '', externalCode: null, contactEmail: null, status: 'ACTIVE' });
      }
      for (const req of http.match((r) => r.url.startsWith('/api/v1/portfolios/'))) {
        req.flush({ id: 'p-1', creditorId: 'c-1', contractId: 'k', code: 'BH-09', name: 'Cartões setembro', description: null, receivedOn: '2026-01-01', validUntil: null, status: 'ACTIVE', statusReason: null, operable: true });
      }
    }
    fixture.detectChanges();
  }

  it('mostra posição, documento mascarado e títulos com credor e saldo', async () => {
    permissions.set(['DEBTOR_VIEW']);
    const fixture = await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Ana Paula Ribeiro');
    expect(text).toContain('***.982.247-**');
    expect(text).toContain('CARTAO-4471');
    expect(text).toContain('Horizonte');
    expect(text).toContain('135 dias');
    expect(text).toMatch(/R\$\s3\.120,45/);
    // sem DEBTOR_SENSITIVE_VIEW não há botão de revelar nem de editar
    expect(fixture.nativeElement.querySelector('.app-document__reveal')).toBeNull();
    expect(text).not.toContain('Editar dados');
  });

  it('revela o documento só sob demanda (GET auditado) e mantém o valor apenas na tela', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBTOR_SENSITIVE_VIEW', 'DEBTOR_MANAGE']);
    const fixture = await render();
    const reveal = fixture.nativeElement.querySelector('.app-document__reveal') as HTMLButtonElement;
    expect(reveal).not.toBeNull();
    reveal.click();
    http.expectOne({ method: 'GET', url: '/api/v1/debtors/d-1/document' }).flush({ debtorId: 'd-1', documentType: 'CPF', document: '529.982.247-25' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('529.982.247-25');

    const hide = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.trim() === 'Ocultar') as HTMLButtonElement;
    hide.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('529.982.247-25');
    expect(fixture.nativeElement.textContent).toContain('Editar dados');
  });

  it('descarta a revelação que chega depois da troca de devedor (o CPF de um não aparece na ficha do outro)', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBTOR_SENSITIVE_VIEW']);
    const fixture = await render();
    (fixture.nativeElement.querySelector('.app-document__reveal') as HTMLButtonElement).click();
    const pending = http.expectOne({ method: 'GET', url: '/api/v1/debtors/d-1/document' });

    fixture.componentRef.setInput('id', 'd-2');
    fixture.detectChanges();
    const other = { ...debtor, id: 'd-2', name: 'Bruno Costa Lima', maskedDocument: '***.111.444-**' };
    http.expectOne('/api/v1/debtors/d-2').flush(other);
    http.expectOne('/api/v1/debtors/d-2/summary').flush({ ...summary, debtorId: 'd-2', name: other.name, maskedDocument: other.maskedDocument });
    http.expectOne('/api/v1/debtors/d-2/titles').flush([]);
    http.expectOne('/api/v1/debtors/d-2/contacts').flush([]);
    await settle(fixture);

    pending.flush({ debtorId: 'd-1', documentType: 'CPF', document: '529.982.247-25' });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Bruno Costa Lima');
    expect(text).not.toContain('529.982.247-25');
    expect(text).toContain('***.111.444-**');
  });

  it('com casos ativos: "Abrir caso" para escolher, "Simular" e "Relacionados" só com o que o perfil abre', async () => {
    permissions.set(['DEBTOR_VIEW', 'CASE_VIEW', 'NEGOTIATION_SIMULATE']);
    const fixture = await render([caseOf('k-1', 'IN_NEGOTIATION'), caseOf('k-2', 'OPEN', 'p-2'), caseOf('k-3', 'CLOSED')]);
    const el = fixture.nativeElement as HTMLElement;
    const header = el.querySelector('app-page-header') as HTMLElement;
    expect(header.textContent).toContain('Abrir caso (2)');
    expect(header.textContent).toContain('Simular');

    const related = el.querySelector('app-related-records') as HTMLElement;
    const links = [...related.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toContain('/cobranca/casos/k-1');
    expect(links).toContain('/cobranca/casos/k-3');
    expect(links).toContain('/titulos?devedor=d-1');
    // sem AGREEMENT_VIEW, PAYMENT_VIEW, LEGAL_VIEW ou INBOX_VIEW: nada buscado nem exibido desses módulos
    expect(related.textContent).not.toContain('Acordos');
    expect(related.textContent).not.toContain('Pagamentos');
    // aba de interações (CASE_VIEW)
    expect(el.querySelector('[data-tour="debtor-interactions"]')).not.toBeNull();
  });

  it('um único caso ativo abre direto; sem CASE_VIEW não há "Abrir caso" nem aba de interações', async () => {
    permissions.set(['DEBTOR_VIEW', 'CASE_VIEW']);
    const fixture = await render([caseOf('k-1', 'OPEN')]);
    const open = [...(fixture.nativeElement as HTMLElement).querySelectorAll('app-page-header a')].find((a) => a.textContent?.includes('Abrir caso'));
    expect(open?.getAttribute('href')).toBe('/cobranca/casos/k-1');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Simular');

    permissions.set(['DEBTOR_VIEW']);
    const other = await render();
    expect((other.nativeElement as HTMLElement).querySelector('app-page-header')?.textContent).not.toContain('Abrir caso');
    expect((other.nativeElement as HTMLElement).querySelector('[data-tour="debtor-interactions"]')).toBeNull();
  });
  it('aba Interações: uma chamada só, pelo devedor (todos os casos do escopo)', async () => {
    permissions.set(['DEBTOR_VIEW', 'CASE_VIEW']);
    const fixture = await render([caseOf('k-1', 'OPEN'), caseOf('k-2', 'CLOSED')]);
    (fixture.nativeElement.querySelector('[data-tour="debtor-interactions"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === '/api/v1/collection/debtors/d-1/interactions');
    expect(req.request.params.get('size')).toBe('50');
    req.flush({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    http.expectOne((r) => r.url === '/api/v1/collection/tabulations').flush([]);
    http.expectNone((r) => r.url.includes('/collection/cases/'));
    await settle(fixture);
  });

  it('?aba=interacoes sem CASE_VIEW volta para Títulos', async () => {
    permissions.set(['DEBTOR_VIEW']);
    await TestBed.inject(Router).navigateByUrl('/?aba=interacoes');
    const fixture = await render();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.nav-link.active')?.textContent).toContain('Títulos');
  });
});

describe('DebtorDetail embutida no painel da estação', () => {
  let http: HttpTestingController;

  it('não oferece "Abrir caso" nem "Simular" (o operador continua no caso atual)', async () => {
    TestBed.configureTestingModule({
      imports: [DebtorDetail],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: URL_STATE_EMBEDDED, useValue: true },
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser()), hasPermission: () => true, hasAnyPermission: () => true },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DebtorDetail);
    fixture.componentRef.setInput('id', 'd-1');
    fixture.detectChanges();
    http.expectOne('/api/v1/debtors/d-1').flush(debtor);
    http.expectOne('/api/v1/debtors/d-1/summary').flush(summary);
    http.expectOne('/api/v1/debtors/d-1/titles').flush([title]);
    http.expectOne('/api/v1/debtors/d-1/contacts').flush([contact]);
    http.expectOne('/api/v1/collection/debtors/d-1/cases').flush([caseOf('k-1', 'OPEN')]);
    for (let i = 0; i < 5; i++) {
      fixture.detectChanges();
      await Promise.resolve();
      for (const req of http.match(() => true)) {
        const url = req.request.url;
        req.flush(url.includes('/disputes') ? [] : url.match(/\/(creditors|portfolios)\//) ? { id: 'x', name: 'X', tradeName: 'X' } : { content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
      }
    }
    fixture.detectChanges();
    const header = (fixture.nativeElement as HTMLElement).querySelector('app-page-header')?.textContent ?? '';
    expect(header).not.toContain('Abrir caso');
    expect(header).not.toContain('Simular');
    // o vínculo explícito continua no "Relacionados"
    expect((fixture.nativeElement as HTMLElement).querySelector('app-related-records a[href="/cobranca/casos/k-1"]')).not.toBeNull();
  });
});
