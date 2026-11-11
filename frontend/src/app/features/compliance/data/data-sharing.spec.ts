import { Routes } from '@angular/router';
import { ASSISTANT_ROUTES } from '../../assistant/assistant.routes';
import { COMMUNICATION_ROUTES } from '../../communication/communication.routes';
import { PAYMENT_ROUTES } from '../../payment/payment.routes';
import { TELEPHONY_ROUTES } from '../../telephony/telephony.routes';
import { COMPLIANCE_ROUTES } from '../compliance.routes';
import { DataSubjectRequestView, DraftLinkView } from './compliance.models';
import { PROCESSORS_PERMISSIONS, PROCESSORS_ROUTE, canOpenLink, countryName, draftAvailability, providerConfig } from './data-sharing';
import { customKey } from '../processors/processor-form';

const base: DataSubjectRequestView = {
  id: 'q-1',
  type: 'INFORMATION_SHARING',
  channel: 'EMAIL',
  status: 'IDENTITY_VERIFIED',
  debtorId: 'd-1',
  requesterName: 'Maria Titular',
  maskedDocument: null,
  maskedContact: null,
  details: null,
  receivedAt: '2026-11-01T12:00:00Z',
  dueAt: '2026-11-16T12:00:00Z',
  overdue: false,
  assignedTo: null,
  identityVerifiedAt: '2026-11-02T12:00:00Z',
  identityVerifiedBy: null,
  executedAt: null,
  completedAt: null,
  responseSummary: null,
  hasResultFile: false,
  resultSha256: null,
};

describe('resposta sugerida ao titular (regras da tela)', () => {
  it('aparece só em confirmação e compartilhamento abertos, para quem atua em todos os credores', () => {
    expect(draftAvailability(base, true)).toEqual({ show: true, ready: false, hint: 'Inicie o atendimento para usar a resposta sugerida na conclusão.' });
    expect(draftAvailability({ ...base, status: 'IN_PROGRESS' }, true)).toEqual({ show: true, ready: true, hint: null });
    expect(draftAvailability({ ...base, identityVerifiedAt: null, status: 'RECEIVED' }, true).hint).toContain('Confirme a identidade');
    expect(draftAvailability({ ...base, type: 'ACCESS' }, true).show).toBe(false);
    expect(draftAvailability({ ...base, status: 'COMPLETED' }, true).show).toBe(false);
    expect(draftAvailability(base, false).show).toBe(false);
  });

  it('link da evidência segue a regra do permissionGuard (uma de any e todas de all)', () => {
    const link: DraftLinkView = { route: '/telefonia/chamadas', queryParams: { devedor: 'd-1' }, anyPermission: ['TELEPHONY_SUPERVISE'], allPermissions: ['DEBTOR_VIEW'], label: 'Ver' };
    expect(canOpenLink(link, (p) => p === 'TELEPHONY_SUPERVISE')).toBe(false);
    expect(canOpenLink(link, (p) => ['TELEPHONY_SUPERVISE', 'DEBTOR_VIEW'].includes(p))).toBe(true);
  });

  it('cada prestador leva à tela onde é configurado', () => {
    expect(providerConfig('communication.whatsapp_cloud')?.route).toBe('/conversas/canais');
    expect(providerConfig('telephony.twilio')?.permissions).toEqual(['TELEPHONY_MANAGE']);
    expect(providerConfig('payment.pix_account:6f1c2b9e-1d1a-4c55-9c1e-2b7d1c0a9e11')?.route).toBe('/pagamentos/contas-pix');
    expect(providerConfig('assistant.bedrock')?.route).toBe('/assistente/politicas');
    expect(providerConfig('custom.hospedagem')).toBeNull();
  });

  it('os atalhos exigem as mesmas permissões que as rotas de destino (permissionGuard)', () => {
    const routes: Record<string, Routes> = {
      conversas: COMMUNICATION_ROUTES,
      telefonia: TELEPHONY_ROUTES,
      pagamentos: PAYMENT_ROUTES,
      assistente: ASSISTANT_ROUTES,
      compliance: COMPLIANCE_ROUTES,
    };
    const permissionsOf = (url: string) => {
      const [, prefix, ...rest] = url.split('/');
      return routes[prefix].find((r) => r.path === rest.join('/'))?.data?.['permissions'];
    };
    for (const key of ['communication.smtp', 'telephony.twilio', 'payment.pix_account:6f1c2b9e-1d1a-4c55-9c1e-2b7d1c0a9e11', 'assistant.bedrock']) {
      const config = providerConfig(key)!;
      expect([...config.permissions].sort(), key).toEqual([...permissionsOf(config.route)].sort());
    }
    expect([...PROCESSORS_PERMISSIONS]).toEqual([...permissionsOf(PROCESSORS_ROUTE)]);
    expect([...PROCESSORS_PERMISSIONS]).toEqual([...permissionsOf('/compliance/bases-legais')]);
  });

  it('país em português e identificador do operador manual sem acentos', () => {
    expect(countryName('US')).toBe('Estados Unidos');
    expect(countryName(null)).toBe('—');
    expect(customKey('Nuvem Fictícia S.A.')).toBe('custom.nuvem_ficticia_s_a');
  });
});
