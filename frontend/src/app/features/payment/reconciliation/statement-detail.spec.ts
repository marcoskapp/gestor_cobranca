import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { statementImport } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { StatementImportView } from '../data/payment.models';
import { StatementDetail } from './statement-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('StatementDetail', () => {
  let http: HttpTestingController;
  let permissions: string[] = [];
  const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };

  beforeEach(() => {
    permissions = ['RECONCILIATION_MANAGE'];
    TestBed.configureTestingModule({
      imports: [StatementDetail],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
        {
          provide: AuthService,
          useFactory: () => ({
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          }),
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  async function flushGets(fixture: ComponentFixture<StatementDetail>, view: StatementImportView): Promise<void> {
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === `/api/v1/payment/reconciliation/statements/${view.id}`) req.flush(view);
        else if (url.endsWith('/lines')) req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
        else if (url === '/api/v1/users/display-names') req.flush([{ id: 'u-1', name: 'Diego Financeiro' }]);
        else req.flush({});
      }
    }
    fixture.detectChanges();
  }

  async function render(view: StatementImportView): Promise<ComponentFixture<StatementDetail>> {
    const fixture = TestBed.createComponent(StatementDetail);
    fixture.componentRef.setInput('id', view.id);
    await flushGets(fixture, view);
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const retryButton = (f: ComponentFixture<unknown>) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Tentar novamente') && b.closest('app-page-header'));

  const interrupted = statementImport({ status: 'FAILED', failureCode: 'statement.processing_failed', failureLine: 502, totalLines: 500, finishedAt: '2026-10-23T10:05:00Z' });

  it('importação interrompida: mostra onde parou e envia a nova tentativa', async () => {
    const fixture = await render(interrupted);
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('parou na linha 502');
    expect(text).toContain('500 linhas já gravadas');
    const button = retryButton(fixture)!;
    expect(button).toBeDefined();

    button.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === `/api/v1/payment/reconciliation/statements/${interrupted.id}/retry`);
    req.flush({ ...interrupted, status: 'RECEIVED', failureCode: null, failureLine: null, retryCount: 1 }, { status: 202, statusText: 'Accepted' });
    await flushGets(fixture, { ...interrupted, status: 'PROCESSING', failureCode: null, failureLine: null, retryCount: 1 });
    expect(notifications.success).toHaveBeenCalled();
    expect(retryButton(fixture)).toBeUndefined();
    expect(el(fixture).textContent).toContain('Conciliando as linhas');
  });

  it('arquivo recusado: aponta a linha e o motivo', async () => {
    const fixture = await render(statementImport({ status: 'FAILED', failureCode: 'statement.date_invalid', failureLine: 3, totalLines: 0 }));
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('recusado na linha 3');
    expect(text).toContain('Data inválida');
    expect(text).toContain('Nenhuma linha foi conciliada');
    expect(retryButton(fixture)).toBeDefined();
  });

  it('nova tentativa recusada (já retomada): avisa e recarrega', async () => {
    const fixture = await render(interrupted);
    retryButton(fixture)!.click();
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('/retry'))
      .flush({ type: 'about:blank', status: 409, code: 'statement.not_failed', detail: 'x' }, { status: 409, statusText: 'Conflict' });
    await flushGets(fixture, { ...interrupted, status: 'PROCESSING' });
    expect(notifications.error).toHaveBeenCalledWith(expect.stringContaining('não está com falha'));
  });

  it('sem a permissão de importar extrato, ou fora de FAILED, o botão não aparece', async () => {
    permissions = [];
    const fixture = await render(interrupted);
    expect(retryButton(fixture)).toBeUndefined();

    permissions = ['RECONCILIATION_MANAGE'];
    const processed = await render(statementImport());
    expect(retryButton(processed)).toBeUndefined();
  });
});
