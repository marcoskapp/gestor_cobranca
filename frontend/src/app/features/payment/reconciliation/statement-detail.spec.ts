import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { statementImport } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { StatementImportView } from '../data/payment.models';
import { StatementDetail } from './statement-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('StatementDetail', () => {
  let http: HttpTestingController;
  let permissions: string[] = [];
  const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const confirm = { confirm: vi.fn(() => of('Extrato errado enviado')) };

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
        { provide: ConfirmService, useValue: confirm },
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

  /** Ação irreversível no "Mais ações" do cabeçalho (ux.md §12.2). */
  const cancelButton = (f: ComponentFixture<unknown>) =>
    [...el(f).querySelectorAll<HTMLButtonElement>('app-page-header app-more-actions .dropdown-item.text-danger-emphasis')].find((b) => b.textContent?.includes('Cancelar processamento'));

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
      .flush({ type: 'about:blank', status: 409, code: 'statement.not_retryable', detail: 'x' }, { status: 409, statusText: 'Conflict' });
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

  // na 1ª tentativa as contagens só são apuradas no fim: durante o processamento ficam em 0
  const processing = statementImport({ status: 'PROCESSING', finishedAt: null, totalLines: 0, reconciledLines: 0, createdPayments: 0, divergentLines: 0, ignoredLines: 0 });

  it('em processamento: cancela com confirmação e motivo, e a tela mostra o cancelamento e o "Tentar novamente"', async () => {
    const fixture = await render(processing);
    expect(retryButton(fixture)).toBeUndefined();
    const button = cancelButton(fixture)!;
    expect(button).toBeDefined();

    button.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true, reason: 'required', details: expect.arrayContaining([{ label: 'Situação', value: 'Em processamento' }]) }));
    const req = http.expectOne((r) => r.method === 'POST' && r.url === `/api/v1/payment/reconciliation/statements/${processing.id}/cancel`);
    expect(req.request.body).toEqual({ reason: 'Extrato errado enviado' });
    const cancelled = { ...processing, status: 'CANCELLED' as const, finishedAt: '2026-10-23T10:03:00Z', totalLines: 500, reconciledLines: 1, createdPayments: 1, ignoredLines: 499 };
    req.flush(cancelled);
    await flushGets(fixture, cancelled);

    expect(notifications.success).toHaveBeenCalled();
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('O processamento foi cancelado');
    expect(text).toContain('500 linhas já gravadas');
    expect(text).toContain('cancelado em');
    expect(cancelButton(fixture)).toBeUndefined();
    expect(retryButton(fixture)).toBeDefined();
  });

  it('importação cancelada: "Tentar novamente" continua de onde parou', async () => {
    const cancelled = statementImport({ status: 'CANCELLED', totalLines: 500 });
    const fixture = await render(cancelled);
    retryButton(fixture)!.click();
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith(`/statements/${cancelled.id}/retry`))
      .flush({ ...cancelled, status: 'RECEIVED', finishedAt: null, retryCount: 1 }, { status: 202, statusText: 'Accepted' });
    await flushGets(fixture, { ...cancelled, status: 'PROCESSING', finishedAt: null, retryCount: 1 });
    expect(retryButton(fixture)).toBeUndefined();
    expect(cancelButton(fixture)).toBeDefined();
  });

  it('cancelamento recusado (já terminou): avisa e recarrega', async () => {
    const fixture = await render(processing);
    cancelButton(fixture)!.click();
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('/cancel'))
      .flush({ type: 'about:blank', status: 409, code: 'statement.not_cancellable', detail: 'x' }, { status: 409, statusText: 'Conflict' });
    await flushGets(fixture, statementImport());
    expect(notifications.error).toHaveBeenCalledWith(expect.stringContaining('já terminou'));
    expect(cancelButton(fixture)).toBeUndefined();
  });

  it('sem a permissão, ou fora da fila e do processamento, o cancelamento não aparece', async () => {
    permissions = [];
    const fixture = await render(processing);
    expect(cancelButton(fixture)).toBeUndefined();
    expect(el(fixture).textContent).toContain('Conciliando as linhas');

    permissions = ['RECONCILIATION_MANAGE'];
    expect(cancelButton(await render(statementImport({ status: 'RECEIVED', finishedAt: null })))).toBeDefined();
    expect(cancelButton(await render(statementImport()))).toBeUndefined();
    expect(cancelButton(await render(interrupted))).toBeUndefined();
  });
});
