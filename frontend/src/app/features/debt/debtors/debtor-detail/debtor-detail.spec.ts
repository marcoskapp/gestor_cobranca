import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthService } from '../../../../core/auth/auth.service';
import { ContactView, DebtorSummary, DebtorView, TitleView } from '../../data/debt.models';
import { DebtorDetail } from './debtor-detail';
import { LEGACY_DIALOG_LAUNCHERS } from '../../../../core/layout/shell/legacy-dialog-launchers';

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
};

describe('DebtorDetail', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DebtorDetail],
      providers: [
        ...LEGACY_DIALOG_LAUNCHERS,
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

  afterEach(() => http.verify());

  /** Responde às cargas da ficha e à resolução de nomes (credor/carteira). */
  async function render(): Promise<ComponentFixture<DebtorDetail>> {
    const fixture = TestBed.createComponent(DebtorDetail);
    fixture.componentRef.setInput('id', 'd-1');
    fixture.detectChanges();
    http.expectOne('/api/v1/debtors/d-1').flush(debtor);
    http.expectOne('/api/v1/debtors/d-1/summary').flush(summary);
    http.expectOne('/api/v1/debtors/d-1/titles').flush([title]);
    http.expectOne('/api/v1/debtors/d-1/contacts').flush([contact]);
    await settle(fixture);
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
});
