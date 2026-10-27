import { button, render, setup } from '../../../../testing/component-harness';
import { agentView } from '../../../../testing/telephony-fixtures';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { TelephonyAccountView } from '../data/telephony.models';
import { AgentEndpointsDialog, endpointMode } from './agent-endpoints-dialog';

const account = (id: string, name: string, provider: TelephonyAccountView['provider']): TelephonyAccountView => ({
  id, provider, creditorId: null, name, callerId: '+551130000000', recordingEnabled: false, recordingNotice: false, credentialKeys: [],
  credentialsUpdatedAt: null, webhookPath: '/w', active: true, createdAt: null, version: 0, providerLabel: name, readiness: 'READY', dialable: true, ratePerMinute: null, webhookUrl: 'https://a/w', consoleEndpoints: [],
});

describe('AgentEndpointsDialog (ramais por plataforma)', () => {
  const close = vi.fn();

  it('modo de cada conta: sem registro = ramal padrão; inativo = fora da plataforma', () => {
    expect(endpointMode(null)).toBe('DEFAULT');
    expect(endpointMode({ id: 'e', agentId: 'a', userId: 'u', accountId: 'acc', extension: '2001', active: true, version: 0 })).toBe('OWN');
    expect(endpointMode({ id: 'e', agentId: 'a', userId: 'u', accountId: 'acc', extension: null, active: false, version: 0 })).toBe('OFF');
  });

  it('salva só as contas alteradas: ramal próprio por PUT e volta ao padrão por DELETE', async () => {
    const { http } = setup(AgentEndpointsDialog, ['TELEPHONY_MANAGE'], 'u-1', [
      {
        provide: DIALOG_DATA,
        useValue: {
          agent: agentView({ userId: 'u-9' }),
          userName: 'Operador X',
          accounts: [account('acc-1', 'Simulador', 'SANDBOX'), account('acc-2', 'Alvaria CX', 'ALVARIA')],
          endpoints: [{ id: 'e-1', agentId: 'ag-1', userId: 'u-9', accountId: 'acc-1', extension: null, active: false, version: 0 }],
        },
      },
      { provide: DialogRef, useValue: { close } },
    ]);
    const fixture = await render(AgentEndpointsDialog, http, () => undefined);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('select')).toHaveLength(2);
    const rows = (fixture.componentInstance as unknown as { rows: { mode: { setValue(v: string): void }; extension: { setValue(v: string): void } }[] }).rows;
    rows[0].mode.setValue('DEFAULT');
    rows[1].mode.setValue('OWN');
    rows[1].extension.setValue('alv-101');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).toBeTruthy();
    button(fixture, 'Salvar ramais')!.click();

    const removal = http.expectOne((r) => r.method === 'DELETE' && r.url.endsWith('/agents/u-9/endpoints/acc-1'));
    const put = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/agents/u-9/endpoints/acc-2'));
    expect(put.request.body).toEqual({ extension: 'alv-101', active: true });
    removal.flush(null);
    put.flush({});
    expect(close).toHaveBeenCalledWith(true);
  });
});
