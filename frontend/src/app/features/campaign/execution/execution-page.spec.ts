import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { campaign, flushAll, page, panel, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideLocale } from '../../../core/config/locale.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EnrollmentView } from '../data/campaign.models';
import { EnrollmentDialog } from './enrollment-dialog';
import { ExecutionPage } from './execution-page';

function enrollment(overrides: Partial<EnrollmentView> = {}): EnrollmentView {
  return {
    id: 'e-1',
    campaignId: 'cp-1',
    cadenceId: 'cd-1',
    caseId: 'k-1',
    debtorId: 'd-1',
    creditorId: 'c-1',
    portfolioId: null,
    status: 'PAUSED',
    experimentId: null,
    variantCode: null,
    control: false,
    enrolledAt: '2026-10-21T12:00:00Z',
    nextStepCode: 'D3',
    nextStepIteration: 0,
    nextRunAt: null,
    lastStepCode: 'D0',
    lastStepAt: '2026-10-21T12:05:00Z',
    stepsExecuted: 1,
    stepsSkipped: 0,
    pauseReason: 'DISPUTE_OPENED',
    pausedAt: '2026-10-22T12:00:00Z',
    resumeAt: null,
    endReason: null,
    endedAt: null,
    observeUntil: null,
    repliedAt: null,
    effectiveContactAt: null,
    lastErrorCode: null,
    lastErrorAt: null,
    version: 2,
    ...overrides,
  };
}

const auth = (permissions: () => string[]) => ({
  provide: AuthService,
  useValue: {
    user: signal(fakeUser()),
    hasPermission: (p: string) => permissions().includes(p),
    hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
  },
});

describe('ExecutionPage', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    permissions.set(['CAMPAIGN_VIEW', 'CAMPAIGN_EXECUTE']);
    TestBed.configureTestingModule({
      imports: [ExecutionPage],
      providers: [provideLocale(), provideRouter([{ path: '**', children: [] }]), provideHttpClient(), provideHttpClientTesting(), auth(permissions)],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const handlers = {
    '/api/v1/campaign/campaigns/cp-1': campaign({ status: 'ACTIVE' }),
    '/api/v1/campaign/campaigns/cp-1/dashboard': panel(),
    '/api/v1/campaign/campaigns/cp-1/enrollments': page([enrollment(), enrollment({ id: 'e-2', debtorId: 'd-1', status: 'FAILED', lastErrorCode: 'message.template_not_found' })]),
    '/api/v1/debtors': page([]),
    '/api/v1/debtors/names': [{ id: 'd-1', name: 'Ana Paula Ribeiro' }],
  };

  async function render(): Promise<ComponentFixture<ExecutionPage>> {
    const fixture = TestBed.createComponent(ExecutionPage);
    fixture.componentRef.setInput('id', 'cp-1');
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick(i === 1 ? 320 : 0);
      flushAll(http, handlers);
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('painel com denominadores explícitos, motivos de etapas puladas e falhas', async () => {
    const fixture = await render();
    const kpis = el(fixture).querySelector('[data-tour="execution-kpis"]')!.textContent!;
    expect(kpis).toContain('340');
    expect(kpis).toContain('90 de 340 (26,5%)');
    const skips = el(fixture).querySelector('[data-tour="execution-skips"]')!.textContent!;
    expect(skips).toContain('Bloqueada pela política de contato');
    expect(skips).toContain('Template não encontrado ou retirado');
    expect(skips).toContain('650 de 700 (92,9%)');
    expect(el(fixture).textContent).toContain('18 caso(s) do segmento não inscritos');
    const rows = el(fixture).querySelector('[data-tour="execution-enrollments"]')!.textContent!;
    expect(rows).toContain('Ana Paula Ribeiro');
    expect(rows).toContain('Contestação aberta');
    expect(rows).toContain('Template não encontrado ou retirado');
  });

  it('reprocessar falhas mostra as causas e recarrega o painel', async () => {
    const fixture = await render();
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Reprocessar falhas')!.click();
    const data = confirm.mock.calls[0][0];
    expect(data.details).toEqual(expect.arrayContaining([{ label: 'Inscrições com falha', value: '12' }, { label: 'Principais causas', value: 'Template não encontrado ou retirado (12)' }]));
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/campaigns/cp-1/reprocess').flush({ campaignId: 'cp-1', status: 'ACTIVE', affected: 12 });
    fixture.detectChanges();
    await tick();
    const reloaded = flushAll(http, handlers).map((r) => r.request.url);
    expect(reloaded).toEqual(expect.arrayContaining(['/api/v1/campaign/campaigns/cp-1/dashboard', '/api/v1/campaign/campaigns/cp-1/enrollments']));
  });

  it('sem CAMPAIGN_EXECUTE não reprocessa', async () => {
    permissions.set(['CAMPAIGN_VIEW']);
    const fixture = await render();
    expect(button(fixture, 'Reprocessar falhas')).toBeUndefined();
  });
});

describe('EnrollmentDialog', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [EnrollmentDialog],
      providers: [
        provideLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        auth(() => ['CAMPAIGN_EXECUTE']),
        { provide: MAT_DIALOG_DATA, useValue: { enrollmentId: 'e-1', debtorName: 'Ana Paula Ribeiro', canExecute: true } },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('linha do tempo das etapas com motivo e encerramento com ENCERRAR', async () => {
    const fixture = TestBed.createComponent(EnrollmentDialog);
    fixture.detectChanges();
    await tick();
    const detail = {
      enrollment: enrollment({ status: 'ACTIVE', pauseReason: null }),
      steps: [
        { id: 's-1', stepCode: 'D0', iteration: 0, actionType: 'SEND_MESSAGE', channel: 'WHATSAPP', outcome: 'EXECUTED', reason: null, reasonDetail: null, messageId: 'm-1', messageStatus: 'READ', taskId: null, variantCode: null, executedAt: '2026-10-21T12:05:00Z' },
        { id: 's-2', stepCode: 'D1', iteration: 0, actionType: 'SEND_MESSAGE', channel: 'SMS', outcome: 'SKIPPED', reason: 'CONTACT_DENIED', reasonDetail: 'OUTSIDE_WINDOW', messageId: null, messageStatus: null, taskId: null, variantCode: null, executedAt: '2026-10-22T12:05:00Z' },
      ],
    };
    http.expectOne('/api/v1/campaign/enrollments/e-1').flush(detail);
    await tick();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Enviar mensagem por WhatsApp');
    expect(text).toContain('Mensagem: lida');
    expect(text).toContain('Bloqueada pela política de contato (outside window)');

    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of('Pedido do credor'));
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Encerrar inscrição'))!.click();
    expect(confirm.mock.calls[0][0]).toMatchObject({ confirmationText: 'ENCERRAR', reason: 'required', destructive: true });
    const stop = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/enrollments/e-1/stop');
    expect(stop.request.body).toEqual({ note: 'Pedido do credor' });
    stop.flush(enrollment({ status: 'ENDED', endReason: 'MANUAL' }));
    fixture.detectChanges();
    await tick();
    http.expectOne('/api/v1/campaign/enrollments/e-1').flush({ ...detail, enrollment: enrollment({ status: 'ENDED', endReason: 'MANUAL' }) });
  });
});
