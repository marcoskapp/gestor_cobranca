import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideAppLocale } from '../../../core/config/app-locale';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { PlatformTokenView } from '../data/platform-billing.models';
import { PlatformTokenPauseDialog, resumeProblem } from './platform-token-pause-dialog';

const BASE = '/api/v1/platform/billing/tenants/t-1/tokens';

const token = { id: 'k-1', name: 'ERP', prefix: 'gck_abcdefghijkl' } as PlatformTokenView;

describe('pausa de token pela plataforma', () => {
  it('retomada automática: vazia, no futuro e em até 90 dias', () => {
    const now = new Date('2026-11-04T12:00:00');
    expect(resumeProblem('', now)).toBeNull();
    expect(resumeProblem('2026-11-05T09:30', now)).toBeNull();
    expect(resumeProblem('2026-11-04T11:00', now)).toContain('no futuro');
    expect(resumeProblem('2027-03-01T00:00', now)).toContain('90 dias');
    expect(resumeProblem('nao-e-data', now)).toContain('inválidas');
  });

  function render(data: object) {
    const close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: { tenantId: 't-1', tenantName: 'Horizonte', ...data } },
        { provide: DialogRef, useValue: { close } },
      ],
    });
    const fixture = TestBed.createComponent(PlatformTokenPauseDialog);
    fixture.detectChanges();
    return { fixture, close, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, selector: string, value: string): void {
    const input = el.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('exige o motivo e pausa o token com a retomada agendada', () => {
    const { fixture, close, http, el } = render({ token });
    expect(el.textContent).toContain('gck_abcdefghijkl');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectNone(`${BASE}/k-1/pause`);

    type(el, 'textarea', ' laço de chamadas ');
    type(el, 'input[type=datetime-local]', '2099-01-01T10:00');
    fixture.detectChanges();
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    // data além de 90 dias: recusada antes de enviar
    http.expectNone(`${BASE}/k-1/pause`);
    fixture.detectChanges();
    expect(el.textContent).toContain('até 90 dias');

    type(el, 'input[type=datetime-local]', '');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne(`${BASE}/k-1/pause`);
    expect(req.request.body).toEqual({ reason: 'laço de chamadas', resumeAt: null });
    req.flush({ ...token, state: 'PAUSED' });
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ state: 'PAUSED' }));
    http.verify();
  });

  it('sem token, pausa todos os tokens ativos da assessoria', () => {
    const { close, http, el } = render({});
    expect(el.textContent).toContain('Pausar todos os tokens');
    type(el, 'textarea', 'contrato vencido');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne(`${BASE}/pause-all`);
    expect(req.request.body).toEqual({ reason: 'contrato vencido', resumeAt: null });
    req.flush({ affected: 2, skipped: 1, tokens: [] });
    expect(close).toHaveBeenCalledWith({ affected: 2, skipped: 1, tokens: [] });
    http.verify();
  });
});
