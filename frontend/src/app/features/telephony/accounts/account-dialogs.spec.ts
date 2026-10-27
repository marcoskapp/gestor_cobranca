import { ComponentFixture } from '@angular/core/testing';
import { render, setup, text } from '../../../../testing/component-harness';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { TelephonyAccountView, TelephonyProviderView } from '../data/telephony.models';
import { TelephonyAccountDialog, WebhookUrlsDialog } from './account-dialogs';

const providers: TelephonyProviderView[] = [
  {
    provider: 'TWILIO', label: 'Twilio', requiredCredentials: ['accountSid', 'apiKeySecret', 'apiKeySid', 'authToken'], optionalCredentials: ['twimlAppSid'],
    readiness: 'READY', dialable: true, available: true, verifiable: true,
  },
  { provider: 'PLIVO', label: 'Plivo', requiredCredentials: ['authId', 'authToken'], optionalCredentials: [], readiness: 'READY', dialable: true, available: true, verifiable: true },
  { provider: 'TARIFANDO', label: 'Tarifando', requiredCredentials: ['apiToken'], optionalCredentials: [], readiness: 'NOT_IMPLEMENTED', dialable: false, available: false, verifiable: false },
];

function input(fixture: ComponentFixture<unknown>, label: string): HTMLInputElement {
  const field = [...(fixture.nativeElement as HTMLElement).querySelectorAll('app-field')].find((f) => f.textContent?.includes(label));
  return field!.querySelector('input, select') as HTMLInputElement;
}

function type(fixture: ComponentFixture<unknown>, label: string, value: string): void {
  const el = input(fixture, label);
  el.value = value;
  el.dispatchEvent(new Event('input'));
  el.dispatchEvent(new Event('change'));
}

describe('TelephonyAccountDialog (conta por plataforma)', () => {
  const close = vi.fn();

  function create() {
    return setup(TelephonyAccountDialog, ['TELEPHONY_MANAGE'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { account: null, providers } },
      { provide: DialogRef, useValue: { close } },
    ]);
  }

  afterEach(() => vi.clearAllMocks());

  it('Twilio: passo a passo, credenciais obrigatórias e o TwiML App opcional; integração indisponível não aparece', async () => {
    const { http } = create();
    const fixture = await render(TelephonyAccountDialog, http, () => undefined);
    const page = text(fixture);
    expect(page).toContain('Como ligar Twilio');
    expect(page).toContain('API Key');
    expect(page).toContain('Account SID (AC…)');
    expect(page).toContain('TwiML App SID');
    expect(page).toContain('Opcional');
    const options = [...(fixture.nativeElement as HTMLElement).querySelectorAll('option')].map((o) => o.textContent?.trim());
    expect(options).toEqual(['Twilio', 'Plivo']);
  });

  it('Twilio: envia só as credenciais preenchidas (opcional em branco fica de fora)', async () => {
    const { http } = create();
    const fixture = await render(TelephonyAccountDialog, http, () => undefined);
    type(fixture, 'Nome da conta', 'Twilio SP');
    type(fixture, 'Número de origem', '+551140000001');
    type(fixture, 'Account SID', 'AC' + '0'.repeat(32));
    type(fixture, 'API Key SID', 'SK' + '1'.repeat(32));
    type(fixture, 'Segredo da API Key', 'segredo');
    type(fixture, 'Auth Token', 'token');
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/v1/telephony/accounts' });
    expect(req.request.body.provider).toBe('TWILIO');
    expect(req.request.body.credentials).toEqual({ accountSid: 'AC' + '0'.repeat(32), apiKeySecret: 'segredo', apiKeySid: 'SK' + '1'.repeat(32), authToken: 'token' });
    req.flush({ id: 'acc-1' });
    expect(close).toHaveBeenCalled();
  });

  it('Plivo: troca o passo a passo e as credenciais', async () => {
    const { http } = create();
    const fixture = await render(TelephonyAccountDialog, http, () => undefined);
    const select = input(fixture, 'Plataforma') as unknown as HTMLSelectElement;
    select.value = select.options[1].value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const page = text(fixture);
    expect(page).toContain('Como ligar Plivo');
    expect(page).toContain('Auth ID (MA… ou SA…)');
    expect(page).toContain('phone.plivo.com');
    expect(page).not.toContain('Account SID');
  });
});

describe('WebhookUrlsDialog', () => {
  it('lista o webhook da conta e os endereços do console', async () => {
    const account = {
      id: 'acc-1', name: 'Twilio SP', provider: 'TWILIO', webhookUrl: 'https://app.teste/api/v1/webhooks/telephony/rk',
      consoleEndpoints: [{ purpose: 'inbound', label: 'Número → Voice', method: 'POST', url: 'https://app.teste/api/v1/webhooks/telephony/rk?k=inbound' }],
    } as unknown as TelephonyAccountView;
    const { http } = setup(WebhookUrlsDialog, ['TELEPHONY_MANAGE'], 'u-1', [
      { provide: DIALOG_DATA, useValue: { account } },
      { provide: DialogRef, useValue: { close: vi.fn() } },
    ]);
    const fixture = await render(WebhookUrlsDialog, http, () => undefined);
    const urls = [...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="console-url"]')].map((e) => e.textContent);
    expect(urls).toEqual(['https://app.teste/api/v1/webhooks/telephony/rk', 'https://app.teste/api/v1/webhooks/telephony/rk?k=inbound']);
  });
});
