import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { NotificationService } from '../notification/notification.service';
import { errorInterceptor } from './error.interceptor';
import { silentErrorContext } from './http-context';
import { toApiError, userMessage } from './problem';

describe('errorInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  const notifications = { error: vi.fn(), success: vi.fn(), info: vi.fn() };

  beforeEach(() => {
    notifications.error.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => http.verify());

  it('exibe o detail do Problem Details e repassa o erro', async () => {
    const call = firstValueFrom(client.get('/api/v1/creditors/1'));
    http
      .expectOne('/api/v1/creditors/1')
      .flush({ code: 'creditor.not_found', detail: 'Credor não encontrado' }, { status: 404, statusText: 'Not Found' });
    await expect(call).rejects.toMatchObject({ status: 404 });
    expect(notifications.error).toHaveBeenCalledWith('Credor não encontrado');
  });

  it('em 5xx mostra o requestId para o suporte, sem detalhes internos', async () => {
    const call = firstValueFrom(client.get('/api/v1/users'));
    http
      .expectOne('/api/v1/users')
      .flush({ code: 'internal_error', detail: 'stacktrace…', requestId: 'req-9f1' }, { status: 500, statusText: 'Error' });
    await expect(call).rejects.toBeTruthy();
    const message = notifications.error.mock.calls[0][0] as string;
    expect(message).toContain('req-9f1');
    expect(message).not.toContain('stacktrace');
  });

  it('403 gera mensagem clara de acesso negado', async () => {
    const call = firstValueFrom(client.get('/api/v1/users'));
    http.expectOne('/api/v1/users').flush({ code: 'access.denied', detail: 'Acesso negado' }, { status: 403, statusText: 'Forbidden' });
    await expect(call).rejects.toBeTruthy();
    expect(notifications.error.mock.calls[0][0]).toMatch(/permissão/);
  });

  it('não notifica 401 (tratado pelo authInterceptor) nem chamadas silenciosas', async () => {
    const a = firstValueFrom(client.get('/api/v1/users'));
    http.expectOne('/api/v1/users').flush(null, { status: 401, statusText: 'Unauthorized' });
    await expect(a).rejects.toBeTruthy();

    const b = firstValueFrom(client.post('/api/v1/me/password', {}, { context: silentErrorContext() }));
    http.expectOne('/api/v1/me/password').flush({ detail: 'x' }, { status: 422, statusText: 'Unprocessable' });
    await expect(b).rejects.toBeTruthy();

    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('normaliza erros de validação por campo', async () => {
    const call = firstValueFrom(client.post('/api/v1/users', {}));
    http.expectOne('/api/v1/users').flush(
      { code: 'validation.failed', detail: 'Dados inválidos', errors: [{ field: 'email', message: 'inválido' }, { bad: 1 }] },
      { status: 400, statusText: 'Bad Request' },
    );
    const error = await call.catch((e: unknown) => toApiError(e));
    expect(error).toMatchObject({ status: 400, code: 'validation.failed', fieldErrors: [{ field: 'email', message: 'inválido' }] });
    expect(userMessage(error as ReturnType<typeof toApiError>)).toMatch(/campos destacados/);
  });
});
