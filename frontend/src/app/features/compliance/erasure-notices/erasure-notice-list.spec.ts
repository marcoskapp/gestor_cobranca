import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { PageResponse } from '../../../shared/models/page';
import { ErasureNoticeRemittance, ErasureNoticeSummary } from '../data/compliance.models';
import { ErasureNoticeList } from './erasure-notice-list';

const BASE = '/api/v1/compliance/creditor-erasure-notices';

function page(content: ErasureNoticeSummary[]): PageResponse<ErasureNoticeSummary> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 };
}

const pending: ErasureNoticeSummary = {
  id: 'n-1',
  debtorId: 'd-1',
  creditorId: 'c-1',
  creditorName: 'Banco A',
  origin: 'DATA_SUBJECT_REQUEST',
  originId: 'q-1',
  erasedAt: '2026-11-20T12:00:00Z',
  status: 'PENDING',
  notifiedAt: null,
  notifiedChannel: null,
};

const remittance: ErasureNoticeRemittance = {
  creditorId: 'c-1',
  creditorName: 'Banco A',
  noticeIds: ['n-1'],
  titles: 2,
  pendingTotal: 1,
  latestErasedAt: '2026-11-20T12:00:00Z',
  message: 'Prezados(as), Banco A,\n- Título R-7, parcela 1',
};

describe('ErasureNoticeList', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'] }));

  beforeEach(() => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));
    TestBed.configureTestingModule({
      imports: [ErasureNoticeList],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user, hasPermission: () => true, hasAnyPermission: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function settle(fixture: ComponentFixture<ErasureNoticeList>): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('mostra os credores a avisar e, ao escolher um, o aviso pronto com o registro', async () => {
    const fixture = TestBed.createComponent(ErasureNoticeList);
    fixture.detectChanges();
    http.expectOne(`${BASE}/pending-by-creditor`).flush([
      { creditorId: 'c-1', creditorName: 'Banco A', pending: 1, oldestErasedAt: '2026-11-20T12:00:00Z' },
    ]);
    http.expectOne((r) => r.url === BASE).flush(page([pending]));
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Credores a avisar');
    expect(el.textContent).toContain('A avisar');

    (el.querySelector('[data-tour="notice-creditors"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    http.expectOne((r) => r.url === `${BASE}/remittance` && r.params.get('creditorId') === 'c-1').flush(remittance);
    for (const req of http.match((r) => r.url === BASE)) {
      expect(req.request.params.get('creditorId')).toBe('c-1');
      req.flush(page([pending]));
    }
    // o seletor de credor resolve o nome do credor escolhido
    for (const req of http.match('/api/v1/creditors/c-1')) {
      req.flush({ id: 'c-1', legalName: 'Banco A S.A.', tradeName: 'Banco A' });
    }
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    for (const req of http.match('/api/v1/creditors/c-1')) {
      req.flush({ id: 'c-1', legalName: 'Banco A S.A.', tradeName: 'Banco A' });
    }
    await settle(fixture);
    expect(el.querySelector('.cmp-notice-text')?.textContent).toContain('Título R-7, parcela 1');
    expect(el.textContent).toContain('Registrar aviso');
    expect(el.textContent).toContain('Copiar texto');
  });

  it('sem acesso a todos os credores explica a restrição e não consulta nada', async () => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: false }));
    const fixture = TestBed.createComponent(ErasureNoticeList);
    await settle(fixture);
    http.expectNone((r) => r.url.startsWith(BASE));
    expect(fixture.nativeElement.textContent).toContain('exigem acesso a todos os credores');
  });
});
