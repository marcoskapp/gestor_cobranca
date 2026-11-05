import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../../core/auth/auth.models';
import { AuthService } from '../../../../core/auth/auth.service';
import { DataSubjectRequestView } from '../../data/compliance.models';
import { RequestDetail } from './request-detail';

const BASE = '/api/v1/compliance/data-subject-requests';

const executed: DataSubjectRequestView = {
  id: 'q-9',
  type: 'ACCESS',
  channel: 'EMAIL',
  status: 'IN_PROGRESS',
  debtorId: null,
  requesterName: 'Carlos Lima',
  maskedDocument: '***.211.306-**',
  maskedContact: null,
  details: null,
  receivedAt: '2026-09-01T12:00:00Z',
  dueAt: '2026-09-16T12:00:00Z',
  overdue: false,
  assignedTo: null,
  identityVerifiedAt: '2026-09-02T12:00:00Z',
  identityVerifiedBy: null,
  executedAt: '2026-09-03T12:00:00Z',
  completedAt: null,
  responseSummary: null,
  hasResultFile: true,
  resultSha256: 'a'.repeat(64),
};

describe('RequestDetail', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'] }));

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RequestDetail],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user, hasPermission: () => false, hasAnyPermission: () => false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(): Promise<ComponentFixture<RequestDetail>> {
    const fixture = TestBed.createComponent(RequestDetail);
    fixture.componentRef.setInput('id', executed.id);
    fixture.detectChanges();
    http.expectOne(`${BASE}/${executed.id}`).flush(executed);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  function downloadButton(fixture: ComponentFixture<RequestDetail>): HTMLButtonElement | undefined {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
      b.textContent?.includes('Baixar dados do titular'),
    );
  }

  it('com acesso a todos os credores mostra o download do arquivo do titular', async () => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));
    const fixture = await render();
    expect(downloadButton(fixture)).toBeDefined();
  });

  it('sem acesso a todos os credores oculta o download (o arquivo traz todos os credores)', async () => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: false }));
    const fixture = await render();
    expect(downloadButton(fixture)).toBeUndefined();
    expect(fixture.nativeElement.textContent).toContain('Arquivo do titular');
    expect(fixture.nativeElement.textContent).toContain('SHA-256');
  });
});
