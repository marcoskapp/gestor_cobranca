import { Clipboard } from '@angular/cdk/clipboard';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { instruction } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { InstructionDialog, InstructionDialogData } from './instruction-dialog';
import { PENDING_POLL_MS } from './instruction-panel';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('InstructionDialog', () => {
  let http: HttpTestingController;
  const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const copy = vi.fn(() => true);
  const permissions = ['PAYMENT_VIEW', 'PAYMENT_INSTRUCTION_CREATE'];
  const target: InstructionDialogData['target'] = {
    kind: 'INSTALLMENT',
    creditorId: 'c-1',
    agreementId: 'a-1',
    installmentId: 'i-2',
    installmentNumber: 2,
    dueDate: '2026-11-25',
    balance: 1350,
  };

  function setup(data: InstructionDialogData): ComponentFixture<InstructionDialog> {
    TestBed.configureTestingModule({
      imports: [InstructionDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: data },
        { provide: DialogRef, useValue: { close: vi.fn() } },
        { provide: NotificationService, useValue: notifications },
        { provide: Clipboard, useValue: { copy } },
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser()), hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(InstructionDialog);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.verify();
    vi.clearAllMocks();
  });

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const submit = (f: ComponentFixture<unknown>) => el(f).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('gera Pix da parcela com chave de idempotência, acompanha o registro e mostra o copia e cola', async () => {
    const fixture = setup({ target, debtorId: 'd-1', debtorName: 'Ana Paula Ribeiro', channel: 'WHATSAPP' });
    expect(el(fixture).textContent).toContain('2ª parcela do acordo');
    fixture.componentInstance['form'].patchValue({ amount: '1.000,00', expiresInSeconds: 3600 });
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/payment/instructions');
    expect(req.request.body).toEqual({
      type: 'PIX_IMMEDIATE',
      agreementId: 'a-1',
      installmentId: 'i-2',
      titleId: null,
      amount: 1000,
      dueDate: null,
      expiresInSeconds: 3600,
      channel: 'WHATSAPP',
    });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    req.flush(instruction({ status: 'PENDING', pixCopyPaste: null, amount: 1000 }));
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Registrando a cobrança no PSP');

    await tick(PENDING_POLL_MS + 50);
    http.expectOne('/api/v1/payment/instructions/in-1').flush(instruction({ amount: 1000, version: 2 }));
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.ip__code')?.textContent).toContain('br.gov.bcb.pix');
    const copyButton = [...el(fixture).querySelectorAll('button')].find((b) => b.textContent?.includes('Copiar código'))!;
    copyButton.click();
    expect(copy).toHaveBeenCalledWith(instruction().pixCopyPaste);
    expect(notifications.success).toHaveBeenCalled();
    fixture.destroy();
  }, 10_000);

  it('não envia valor acima do saldo e explica a falta de conta Pix', () => {
    const fixture = setup({ target, debtorId: 'd-1' });
    fixture.componentInstance['form'].patchValue({ amount: '2000' });
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('não pode passar do saldo');
    submit(fixture).click();
    http.expectNone((r) => r.method === 'POST');

    fixture.componentInstance['form'].patchValue({ amount: '', type: 'PIX_DUE' });
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body.type).toBe('PIX_DUE');
    expect(req.request.body.amount).toBeNull();
    req.flush({ code: 'payment_instruction.no_pix_account', detail: 'Nenhuma conta Pix ativa' }, { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="alert"]')?.textContent).toContain('Não há conta Pix ativa');
  });

  it('Pix com vencimento: escolhido pelo grupo de botões, envia a data digitada em ISO; boleto indisponível', () => {
    const fixture = setup({ target, debtorId: 'd-1' });
    expect(el(fixture).querySelector<HTMLInputElement>('#id-type-boleto')!.disabled).toBe(true);
    el(fixture).querySelector<HTMLInputElement>('#id-type-due')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance['form'].controls.type.value).toBe('PIX_DUE');
    const due = el(fixture).querySelector<HTMLInputElement>('input[formcontrolname="dueDate"]')!;
    due.value = '15/12/2099';
    due.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body).toMatchObject({ type: 'PIX_DUE', dueDate: '2099-12-15', expiresInSeconds: null });
    req.flush(instruction({ type: 'PIX_DUE', dueDate: '2099-12-15' }));
  });
});
