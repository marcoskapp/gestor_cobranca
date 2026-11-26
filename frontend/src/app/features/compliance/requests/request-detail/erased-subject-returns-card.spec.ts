import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../../core/auth/auth.models';
import { AuthService } from '../../../../core/auth/auth.service';
import { DataSubjectRequestView, ErasedSubjectReturnsView } from '../../data/compliance.models';
import { ErasedSubjectReturnsCard } from './erased-subject-returns-card';
import { requestActions } from './request-detail';

const BASE = '/api/v1/compliance/data-subject-requests';

const refusing: ErasedSubjectReturnsView = {
  requestId: 'q-1',
  refusing: true,
  releasable: true,
  attempts: [
    {
      id: 'a-1',
      attemptedAt: '2026-11-26T12:00:00Z',
      source: 'IMPORT',
      creditorId: null,
      batchId: 'b-1',
      lines: 2,
      outcome: null,
      reviewedAt: null,
      reviewedBy: null,
    },
  ],
  release: null,
};

describe('ErasedSubjectReturnsCard', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ErasedSubjectReturnsCard],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user, hasPermission: () => true, hasAnyPermission: () => false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(view: ErasedSubjectReturnsView): Promise<ComponentFixture<ErasedSubjectReturnsCard>> {
    const fixture = TestBed.createComponent(ErasedSubjectReturnsCard);
    fixture.componentRef.setInput('requestId', 'q-1');
    fixture.detectChanges();
    http.expectOne(`${BASE}/q-1/erased-subject-returns`).flush(view);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('mostra a recusa em vigor, a tentativa a revisar com link para o lote e as duas ações', async () => {
    const el = (await render(refusing)).nativeElement as HTMLElement;
    expect(el.textContent).toContain('recusam esta pessoa');
    expect(el.textContent).toContain('A revisar');
    expect(el.querySelector('a[href="/importacao/b-1"]')?.textContent).toContain('Importação de carteira');
    const buttons = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).toContain('Manter recusa');
    expect(buttons).toContain('Liberar retorno');
  });

  it('quem executou a eliminação não vê "Liberar retorno"; sem pendência, não vê "Manter recusa"', async () => {
    const el = (
      await render({ ...refusing, releasable: false, attempts: [{ ...refusing.attempts[0], outcome: 'KEPT', reviewedAt: '2026-11-26T13:00:00Z', reviewedBy: 'u-1' }] })
    ).nativeElement as HTMLElement;
    const buttons = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).not.toContain('Liberar retorno');
    expect(buttons).not.toContain('Manter recusa');
    expect(el.textContent).toContain('Recusa mantida');
  });

  it('liberado, mostra quando e o motivo, sem ações', async () => {
    const el = (
      await render({
        ...refusing,
        refusing: false,
        releasable: false,
        attempts: [],
        release: { releasedAt: '2026-11-26T14:00:00Z', releasedBy: 'u-2', reason: 'Voltou a contratar com o credor' },
      })
    ).nativeElement as HTMLElement;
    expect(el.textContent).toContain('Retorno liberado em');
    expect(el.textContent).toContain('Voltou a contratar com o credor');
    expect(el.querySelectorAll('button').length).toBe(0);
  });
});

describe('requestActions', () => {
  it('pedido com a ação executada não pode mais ser recusado, só concluído', () => {
    const base = { status: 'IN_PROGRESS', type: 'ANONYMIZATION', hasResultFile: false } as DataSubjectRequestView;
    expect(requestActions({ ...base, executedAt: null }).reject).toBe(true);
    expect(requestActions({ ...base, executedAt: '2026-11-26T12:00:00Z' }).reject).toBe(false);
    expect(requestActions({ ...base, executedAt: '2026-11-26T12:00:00Z' }).complete).toBe(true);
  });
});
