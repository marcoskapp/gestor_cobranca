import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { agreement, proposal } from '../../../../testing/negotiation-fixtures';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { CloseAgreementData, CloseAgreementDialog, acceptanceChannel } from './close-agreement-dialog';

describe('CloseAgreementDialog (fechar o acordo sem sair da estação)', () => {
  let http: HttpTestingController;
  const close = vi.fn();

  function setup(overrides: Partial<CloseAgreementData> = {}): ComponentFixture<CloseAgreementDialog> {
    const data: CloseAgreementData = {
      proposal: proposal({ status: 'DRAFT', channel: null }),
      details: [{ label: 'Total', value: 'R$ 3.000,00' }],
      channel: 'WHATSAPP',
      canFormalize: true,
      canPix: true,
      ...overrides,
    };
    TestBed.configureTestingModule({
      imports: [CloseAgreementDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: data },
        { provide: DialogRef, useValue: { close } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(CloseAgreementDialog);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.verify();
    vi.clearAllMocks();
  });

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const submit = (f: ComponentFixture<unknown>) => el(f).querySelector<HTMLButtonElement>('button[type="submit"]')!;
  const type = (f: ComponentFixture<CloseAgreementDialog>, value: string) => {
    f.componentInstance['form'].controls.typed.setValue(value);
    f.detectChanges();
  };

  it('canal do aceite vem do atendimento; aceite e formalização num passo só, com a confirmação digitada', () => {
    const fixture = setup();
    expect(fixture.componentInstance['form'].controls.channel.value).toBe('WHATSAPP');
    expect(el(fixture).textContent).toContain('Preenchido com o canal do atendimento.');
    expect(submit(fixture).textContent).toContain('Registrar aceite e formalizar');
    expect(submit(fixture).disabled).toBe(true);
    type(fixture, 'formalizar');
    expect(submit(fixture).disabled).toBe(false);

    submit(fixture).click();
    const accept = http.expectOne({ method: 'POST', url: '/api/v1/negotiation/proposals/p-1/accept' });
    expect(accept.request.body).toEqual({ channel: 'WHATSAPP', reference: null });
    accept.flush(proposal({ status: 'ACCEPTED' }));
    http.expectOne({ method: 'POST', url: '/api/v1/negotiation/proposals/p-1/formalize' }).flush(agreement());
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Acordo formalizado');
    expect(el(fixture).textContent).toContain('Pix da 2ª parcela');

    [...el(fixture).querySelectorAll('button')].find((b) => b.textContent?.includes('Gerar Pix'))!.click();
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ pix: true, agreement: expect.objectContaining({ id: 'a-1' }) }));
  });

  it('se a formalização falha depois do aceite, o diálogo continua no passo de formalizar', () => {
    const fixture = setup();
    type(fixture, 'FORMALIZAR');
    submit(fixture).click();
    http.expectOne('/api/v1/negotiation/proposals/p-1/accept').flush(proposal({ status: 'ACCEPTED' }));
    http.expectOne('/api/v1/negotiation/proposals/p-1/formalize').flush({ code: 'proposal.expired' }, { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect(submit(fixture).textContent).toContain('Formalizar');
    expect(el(fixture).textContent).toContain('O aceite do devedor já está registrado.');
    expect(el(fixture).querySelector('select')).toBeNull();

    submit(fixture).click();
    http.expectOne('/api/v1/negotiation/proposals/p-1/formalize').flush(agreement());
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Acordo formalizado');
  });

  it('sem alçada para formalizar: só o aceite, sem pedir a confirmação digitada', () => {
    const fixture = setup({ canFormalize: false, channel: null, proposal: proposal({ status: 'APPROVED', channel: 'PHONE' }) });
    expect(fixture.componentInstance['form'].controls.channel.value).toBe('PHONE');
    expect(el(fixture).textContent).toContain('Sua alçada não formaliza acordos');
    expect(submit(fixture).textContent).toContain('Registrar aceite');
    expect(el(fixture).textContent).not.toContain('para confirmar');
    submit(fixture).click();
    http.expectOne('/api/v1/negotiation/proposals/p-1/accept').flush(proposal({ status: 'ACCEPTED' }));
    http.expectNone('/api/v1/negotiation/proposals/p-1/formalize');
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ agreement: null, proposal: expect.objectContaining({ status: 'ACCEPTED' }) }));
  });

  it('aceite já registrado e sem alçada para formalizar: sem botão de envio, só "Voltar ao caso"', () => {
    const fixture = setup({ canFormalize: false, proposal: proposal({ status: 'ACCEPTED' }) });
    expect(el(fixture).querySelector('button[type="submit"]')).toBeNull();
    expect(el(fixture).textContent).toContain('Sua alçada não formaliza acordos');
    const back = [...el(fixture).querySelectorAll('button')].find((b) => b.textContent?.includes('Voltar ao caso'));
    expect(back).toBeDefined();
    back!.click();
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ agreement: null }));
    http.expectNone('/api/v1/negotiation/proposals/p-1/formalize');
  });

  it('canal do chat do portal não vira canal de aceite da estação', () => {
    expect(acceptanceChannel('PHONE')).toBe('PHONE');
    expect(acceptanceChannel('LETTER')).toBe('');
    expect(acceptanceChannel(null)).toBe('');
  });
});
