import { button, render, setup, text } from '../../../../testing/component-harness';
import { PolicySettings, PolicyView } from '../data/assistant.models';
import { AssistantPolicyDetail } from './assistant-policy-detail';

const settings: PolicySettings = {
  mode: 'SHADOW',
  channels: ['WHATSAPP'],
  serviceHours: [],
  authorityProfileId: null,
  promptCode: null,
  promptVersion: null,
  tone: 'FORMAL',
  maxTurns: 10,
  maxMisunderstandings: 2,
  minConfidence: 0.7,
  requireIdentityVerification: true,
  maxIdentityAttempts: 3,
  allowedTopics: [],
  forbiddenTopics: [],
  extraTriggers: [],
  requiredProfiles: {},
  identificationMessage: null,
  handoffMessage: null,
  fallbackMessage: null,
  approvalMessage: null,
  limits: null,
  reviewSampleRate: 0.1,
  allowCreditBureauMention: false,
};

function policy(authorityProfileId: string | null): PolicyView {
  return {
    id: 'ap-1',
    code: 'PADRAO',
    name: 'Política padrão',
    creditorId: null,
    portfolioId: null,
    policyVersion: 2,
    status: 'DRAFT',
    mode: 'SHADOW',
    settings: { ...settings, authorityProfileId },
    promptCode: 'NEGOCIADOR',
    promptVersion: 1,
    publishedAt: null,
    publishedBy: null,
    retiredAt: null,
    retireReason: null,
    createdAt: '2026-10-01T12:00:00Z',
    version: 0,
  };
}

describe('AssistantPolicyDetail', () => {
  const requested: string[] = [];
  const respond = (current: PolicyView) => (url: string) => {
    requested.push(url);
    if (url === '/api/v1/assistant/policies/ap-1') return current;
    if (url.endsWith('/versions')) return [current];
    if (url.endsWith('/prompts')) return [];
    if (url.endsWith('/authority-profiles')) return [{ id: 'pf-1', code: 'ALCADA_A', name: 'Alçada ampliada', maxInstallments: 12, canFormalize: true }];
    return undefined;
  };

  beforeEach(() => (requested.length = 0));

  it('com gestão de alçadas, escolhe o perfil e publica', async () => {
    const { http } = setup(AssistantPolicyDetail, ['ASSISTANT_MANAGE', 'AUTHORITY_MANAGE']);
    const fixture = await render(AssistantPolicyDetail, http, respond(policy('pf-1')), { id: 'ap-1' });
    expect(text(fixture)).toContain('Alçada ampliada (ALCADA_A)');
    expect(button(fixture, 'Publicar')).toBeDefined();
  });

  it('sem gestão de alçadas: o perfil só é informado, sem consulta da lista de perfis nem aviso de permissão', async () => {
    const { http } = setup(AssistantPolicyDetail, ['ASSISTANT_MANAGE']);
    const fixture = await render(AssistantPolicyDetail, http, respond(policy(null)), { id: 'ap-1' });
    const field = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[data-tour="assistant-authority"] input[readonly]');
    expect(field?.value).toBe('Padrão (ASSISTENTE_INICIAL)');
    expect(requested.some((u) => u.endsWith('/authority-profiles'))).toBe(false);
    expect(text(fixture)).not.toContain('exige a permissão de gestão de alçadas');
    // sem perfil próprio, publicar não depende de alçada
    expect(button(fixture, 'Publicar')).toBeDefined();
  });

  it('sem gestão de alçadas e com perfil próprio na versão: "Publicar" não aparece (o servidor recusaria)', async () => {
    const { http } = setup(AssistantPolicyDetail, ['ASSISTANT_MANAGE']);
    const fixture = await render(AssistantPolicyDetail, http, respond(policy('pf-1')), { id: 'ap-1' });
    const field = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('[data-tour="assistant-authority"] input[readonly]');
    expect(field?.value).toBe('Perfil próprio da política');
    expect(button(fixture, 'Publicar')).toBeUndefined();
  });
});
