import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../../core/auth/auth.models';
import { AuthService } from '../../../../core/auth/auth.service';
import { PageResponse } from '../../../../shared/models/page';
import { DataSubjectRequestView } from '../../data/compliance.models';
import { RequestList } from './request-list';

const BASE = '/api/v1/compliance/data-subject-requests';

function page(content: DataSubjectRequestView[], total = content.length): PageResponse<DataSubjectRequestView> {
  return { content, page: 0, size: 20, totalElements: total, totalPages: 1 };
}

const overdue: DataSubjectRequestView = {
  id: 'q-1',
  type: 'ANONYMIZATION',
  channel: 'EMAIL',
  status: 'IN_PROGRESS',
  debtorId: 'd-1',
  requesterName: 'Carlos Lima',
  maskedDocument: '***.211.306-**',
  maskedContact: null,
  details: null,
  receivedAt: '2026-09-01T12:00:00Z',
  dueAt: '2026-09-16T12:00:00Z',
  overdue: true,
  assignedTo: null,
  identityVerifiedAt: null,
  identityVerifiedBy: null,
  executedAt: null,
  completedAt: null,
  responseSummary: null,
  hasResultFile: false,
  resultSha256: null,
};

describe('RequestList', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'] }));

  beforeEach(() => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));
    TestBed.configureTestingModule({
      imports: [RequestList],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user, hasPermission: () => true, hasAnyPermission: () => true },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flushCounters(): void {
    for (const req of http.match((r) => r.url === BASE && r.params.get('size') === '1')) {
      req.flush(page([], req.request.params.get('overdue') ? 4 : 1));
    }
  }

  async function render(): Promise<ComponentFixture<RequestList>> {
    const fixture = TestBed.createComponent(RequestList);
    fixture.detectChanges();
    flushCounters();
    return fixture;
  }

  it('lista a fila com o atraso destacado e os indicadores', async () => {
    const fixture = await render();
    http.expectOne((r) => r.url === BASE && r.params.get('size') === '20').flush(page([overdue]));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('td .cmp-due--late')?.textContent).toContain('Em atraso há');
    expect(el.textContent).toContain('Anonimização ou eliminação');
    expect(el.textContent).toContain('***.211.306-**');
    expect(el.querySelector('.cmp-kpi__value.cmp-due--late')?.textContent?.trim()).toBe('4');
  });

  it('busca por documento no corpo do POST /lookup, nunca na URL', async () => {
    const fixture = await render();
    http.expectOne((r) => r.url === BASE && r.params.get('size') === '20').flush(page([]));
    const input = fixture.nativeElement.querySelector('input[formcontrolname="document"]') as HTMLInputElement;
    input.value = '529.982.247-25';
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 350));
    fixture.detectChanges();
    const req = http.expectOne({ method: 'POST', url: `${BASE}/lookup?page=0&size=20` });
    expect(req.request.body).toEqual({ document: '52998224725' });
    expect(req.request.urlWithParams).not.toContain('52998224725');
    req.flush(page([overdue]));
  });

  it('sem acesso a todos os credores explica a restrição e não consulta a fila', async () => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: false }));
    const fixture = await render();
    fixture.detectChanges();
    http.expectNone((r) => r.url === BASE);
    expect(fixture.nativeElement.textContent).toContain('exige acesso a todos os credores');
  });
});
