import { button, render, setup, text } from '../../../../testing/component-harness';
import { RouteView, TelephonyAccountView, TelephonyProviderView } from '../data/telephony.models';
import { parseRate } from './account-dialogs';
import { TelephonyAccounts } from './telephony-accounts';

const account = (o: Partial<TelephonyAccountView>): TelephonyAccountView => ({
  id: 'acc-1', provider: 'SANDBOX', creditorId: null, name: 'Simulador', callerId: '+551130000000', recordingEnabled: false, recordingNotice: false,
  credentialKeys: ['webhookSecret'], credentialsUpdatedAt: '2026-10-26T12:00:00Z', webhookPath: '/api/v1/webhooks/telephony/abc', active: true,
  createdAt: '2026-10-26T12:00:00Z', version: 0, providerLabel: 'Simulador', readiness: 'READY', dialable: true, ratePerMinute: null,
  webhookUrl: 'https://app.teste/api/v1/webhooks/telephony/abc', consoleEndpoints: [], ...o,
});

const accounts = [
  account({}),
  account({ id: 'acc-2', provider: 'ALVARIA', name: 'Alvaria CX', providerLabel: 'Alvaria CX (Aspect)', readiness: 'CONTRACT_PENDING', dialable: false, active: false, ratePerMinute: 0.089 }),
];
const providers: TelephonyProviderView[] = [
  { provider: 'ALVARIA', label: 'Alvaria CX (Aspect)', requiredCredentials: ['apiBaseUrl', 'clientId', 'clientSecret', 'webhookSecret'], readiness: 'CONTRACT_PENDING', dialable: false, available: true, optionalCredentials: [], verifiable: false },
  { provider: 'TARIFANDO', label: 'Tarifando Telecom', requiredCredentials: ['apiBaseUrl', 'apiToken', 'webhookSecret'], readiness: 'NOT_IMPLEMENTED', dialable: false, available: false, optionalCredentials: [], verifiable: false },
];
const routes: RouteView[] = [
  { id: 'r-2', scopeType: 'DEFAULT', scopeId: null, creditorId: null, primaryAccountId: 'acc-1', fallbackAccountId: null, version: 0 },
  { id: 'r-1', scopeType: 'CREDITOR', scopeId: 'cr-1', creditorId: 'cr-1', primaryAccountId: 'acc-2', fallbackAccountId: 'acc-1', version: 0 },
];

describe('TelephonyAccounts (plataformas de telefonia)', () => {
  const respond = (url: string) => {
    if (url.endsWith('/telephony/accounts')) {
      return accounts;
    }
    if (url.endsWith('/accounts/providers')) {
      return providers;
    }
    if (url.endsWith('/telephony/routes')) {
      return routes;
    }
    if (url.endsWith('/dialer-campaigns')) {
      return [];
    }
    if (url.includes('/creditors/')) {
      return { id: 'cr-1', legalName: 'Banco A S.A.', tradeName: 'Banco A' };
    }
    return undefined;
  };

  it('lista as contas das duas plataformas com a situação da integração e a tarifa', async () => {
    const { http } = setup(TelephonyAccounts, ['TELEPHONY_MANAGE']);
    const fixture = await render(TelephonyAccounts, http, respond);
    const page = text(fixture);
    expect(page).toContain('Alvaria CX (Aspect)');
    expect(page).toContain('Aguarda homologação');
    expect(page).toContain('R$ 0,089/min');
    expect(page).toContain('2 regra(s)');
    expect(button(fixture, 'Nova conta')).toBeTruthy();
  });

  it('roteamento: regras da mais específica para o padrão, com a contingência', async () => {
    const { http } = setup(TelephonyAccounts, ['TELEPHONY_MANAGE']);
    const fixture = await render(TelephonyAccounts, http, respond);
    (fixture.componentInstance as unknown as { tab: { set(v: string): void } }).tab.set('routes');
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((r) => setTimeout(r, 30));
    }
    fixture.detectChanges();
    const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.tab-pane.active tbody tr')].map((r) => r.textContent ?? '');
    expect(rows[0]).toContain('Credor');
    expect(rows[0]).toContain('Alvaria CX (Alvaria CX (Aspect)) — inativa');
    expect(rows[0]).toContain('Simulador (Simulador)');
    expect(rows[1]).toContain('Padrão da assessoria');
  });

  it('tarifa: vírgula ou ponto, até 4 casas, vazia = sem tarifa', () => {
    expect(parseRate('0,089')).toBe(0.089);
    expect(parseRate(' ')).toBeNull();
    expect(parseRate('0.00001')).toBeUndefined();
    expect(parseRate('101')).toBeUndefined();
    expect(parseRate('abc')).toBeUndefined();
  });
});
