import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthService } from '../../../../core/auth/auth.service';
import { provideAppLocale } from '../../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../../core/notification/notification.service';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { RestrictionForm, RestrictionFormData } from './restriction-form';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

/** Devedor com títulos na Varejo Estrela e coobrigação no Banco Horizonte; a Rede Sul não aparece. */
const SUMMARY = {
  debtorId: 'd-1',
  name: 'Iara Queiroz Albuquerque',
  creditors: [{ creditorId: 'c-varejo', openBalance: 100, maxDaysOverdue: 10, titles: [] }],
  coObligations: [{ contractId: 'k-1', creditorId: 'c-banco' }],
};
const CREDITORS: Record<string, { tradeName: string; legalName: string }> = {
  'c-varejo': { tradeName: 'Varejo Estrela', legalName: 'Varejo Estrela S.A.' },
  'c-banco': { tradeName: 'Banco Horizonte', legalName: 'Banco Horizonte S.A.' },
};

describe('RestrictionForm', () => {
  let http: HttpTestingController;
  const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
  const close = vi.fn();

  async function setup(allCreditors: boolean, data: RestrictionFormData = { debtorId: 'd-1', debtorName: 'Iara Queiroz Albuquerque' }) {
    TestBed.configureTestingModule({
      imports: [RestrictionForm],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: data },
        { provide: DialogRef, useValue: { close } },
        { provide: NotificationService, useValue: notifications },
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser({ allCreditors })), hasPermission: () => true, hasAnyPermission: () => true },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(RestrictionForm);
    fixture.detectChanges();
    await answerLookups(fixture);
    return fixture;
  }

  async function answerLookups(fixture: ComponentFixture<RestrictionForm>) {
    await tick();
    http.match((r) => r.url.endsWith('/debtors/d-1/summary')).forEach((r) => r.flush(SUMMARY));
    await tick();
    for (const id of Object.keys(CREDITORS)) {
      http.match((r) => r.url.endsWith(`/creditors/${id}`)).forEach((r) => r.flush({ id, ...CREDITORS[id] }));
    }
    await tick();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    vi.clearAllMocks();
  });

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const creditorSelect = (f: ComponentFixture<unknown>) =>
    el(f).querySelector<HTMLSelectElement>('select[formcontrolname="creditor"]')!;
  const optionTexts = (f: ComponentFixture<unknown>) => [...creditorSelect(f).options].map((o) => o.textContent!.trim());
  const summary = (f: ComponentFixture<unknown>) => el(f).querySelector('[role="status"]')!.textContent!.replace(/\s+/g, ' ');

  it('lista só os credores do devedor e o pedido do devedor começa valendo para todos', async () => {
    const fixture = await setup(true);
    expect(optionTexts(fixture)).toEqual(['Todos os credores', 'Banco Horizonte', 'Varejo Estrela']);
    expect(creditorSelect(fixture).value).toBe('');
    expect(summary(fixture)).toContain('bloqueia todos os canais para todos os credores, a partir de agora, sem prazo.');
  });

  it('o resumo do alcance acompanha credor, canal e contato específico', async () => {
    const fixture = await setup(true);
    const select = creditorSelect(fixture);
    select.value = [...select.options].find((o) => o.textContent!.includes('Varejo'))!.value;
    select.dispatchEvent(new Event('change'));
    const channel = el(fixture).querySelector<HTMLSelectElement>('select[formcontrolname="channel"]')!;
    channel.value = [...channel.options].find((o) => o.textContent!.includes('WhatsApp'))!.value;
    channel.dispatchEvent(new Event('change'));
    el(fixture).querySelector<HTMLInputElement>('#restriction-form-specific')!.click();
    fixture.detectChanges();
    expect(summary(fixture)).toContain('bloqueia o canal WhatsApp, somente no telefone informado para Varejo Estrela');
  });

  it('voltar ao tipo pedido do devedor devolve o alcance global', async () => {
    const fixture = await setup(true);
    const select = creditorSelect(fixture);
    const type = el(fixture).querySelector<HTMLSelectElement>('select[formcontrolname="type"]')!;
    type.value = [...type.options].find((o) => o.textContent!.includes('Pedido do credor'))!.value;
    type.dispatchEvent(new Event('change'));
    select.value = [...select.options].find((o) => o.textContent!.includes('Banco'))!.value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(summary(fixture)).toContain('para Banco Horizonte');
    type.value = [...type.options].find((o) => o.textContent!.includes('Pedido do devedor'))!.value;
    type.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(creditorSelect(fixture).value).toBe('');
    expect(summary(fixture)).toContain('para todos os credores');
  });

  it('sem acesso a todos os credores, não oferece o alcance global e exige o credor', async () => {
    const fixture = await setup(false);
    expect(optionTexts(fixture)).toEqual(['Escolha o credor', 'Banco Horizonte', 'Varejo Estrela']);
    expect(creditorSelect(fixture).options[0].disabled).toBe(true);
    el(fixture).querySelector<HTMLTextAreaElement>('textarea')!.value = 'Pediu para não ser contatado.';
    el(fixture).querySelector<HTMLTextAreaElement>('textarea')!.dispatchEvent(new Event('input'));
    el(fixture).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Escolha o credor.');
    http.expectNone((r) => r.url.endsWith('/restrictions'));
  });

  it('envia o credor escolhido e informa o alcance ao salvar', async () => {
    const fixture = await setup(true);
    const select = creditorSelect(fixture);
    select.value = [...select.options].find((o) => o.textContent!.includes('Varejo'))!.value;
    select.dispatchEvent(new Event('change'));
    el(fixture).querySelector<HTMLTextAreaElement>('textarea')!.value = 'Pediu para não ser contatado.';
    el(fixture).querySelector<HTMLTextAreaElement>('textarea')!.dispatchEvent(new Event('input'));
    el(fixture).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    const req = http.expectOne((r) => r.url.endsWith('/restrictions') && r.method === 'POST');
    expect(req.request.body).toMatchObject({ debtorId: 'd-1', creditorId: 'c-varejo', channel: null, type: 'DEBTOR_REQUEST' });
    req.flush({ id: 'r-1', debtorId: 'd-1', creditorId: 'c-varejo', channel: null, contactMasked: null, type: 'DEBTOR_REQUEST' });
    await tick();
    expect(notifications.success).toHaveBeenCalledWith(expect.stringContaining('Varejo Estrela · Todos os canais'));
    expect(close).toHaveBeenCalled();
  });
});
