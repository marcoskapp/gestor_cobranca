import { PolicySettings } from './assistant.models';
import { bars, fractionText, prettyJson, reviewBlocked, reviewProblems, scopeOf, settingsProblems } from './assistant.rules';

function settings(o: Partial<PolicySettings> = {}): PolicySettings {
  return {
    mode: 'SHADOW',
    channels: ['WHATSAPP'],
    serviceHours: [],
    authorityProfileId: null,
    promptCode: 'negociacao',
    promptVersion: 1,
    tone: 'FORMAL',
    maxTurns: 12,
    maxMisunderstandings: 2,
    minConfidence: 0.6,
    requireIdentityVerification: true,
    maxIdentityAttempts: 3,
    allowedTopics: [],
    forbiddenTopics: [],
    extraTriggers: [],
    requiredProfiles: {},
    identificationMessage: 'Olá! Sou a assistente virtual de cobrança. Para falar com um atendente humano, escreva HUMANO.',
    handoffMessage: 'Vou transferir.',
    fallbackMessage: 'Desculpe.',
    approvalMessage: 'Aceite registrado.',
    limits: { maxOutputTokens: 600, maxTokensPerTurn: 8000, maxToolCallsPerTurn: 6, timeoutSeconds: 20, maxCostPerConversation: 1 },
    reviewSampleRate: 0.1,
    allowCreditBureauMention: false,
    ...o,
  };
}

describe('regras do assistente', () => {
  it('valida a política como o domínio (transparência, canais, horários, limites)', () => {
    expect(settingsProblems(settings())).toEqual([]);
    expect(settingsProblems(settings({ identificationMessage: 'Olá, sou a Ana da cobrança.' })).join()).toContain('assistente virtual');
    expect(settingsProblems(settings({ channels: ['EMAIL'] })).join()).toContain('WhatsApp');
    expect(settingsProblems(settings({ serviceHours: [{ days: ['MONDAY'], start: '22:00:00', end: '06:00:00' }] })).join()).toContain('meia-noite');
    expect(settingsProblems(settings({ serviceHours: [{ days: [], start: '08:00:00', end: '18:00:00' }] })).join()).toContain('dias');
    expect(settingsProblems(settings({ maxTurns: 80 })).join()).toContain('Turnos');
    expect(settingsProblems(settings({ minConfidence: 1.5 })).join()).toContain('Confiança');
    expect(settingsProblems(settings({ reviewSampleRate: -0.1 })).join()).toContain('amostragem');
    expect(settingsProblems(settings({ extraTriggers: [{ keyword: 'ab', reasonCode: null }] })).join()).toContain('3 a 60');
    expect(settingsProblems(settings({ requiredProfiles: { DISPUTE: 'sênior!' } })).join()).toContain('Perfil exigido');
    expect(settingsProblems(settings({ limits: { maxOutputTokens: 10, maxTokensPerTurn: null, maxToolCallsPerTurn: null, timeoutSeconds: null, maxCostPerConversation: 0 } })).length).toBe(2);
  });

  it('Pix na conversa: desligado por padrão, exige identidade e limites válidos', () => {
    const pix = { enabled: true, maxAmount: 5000, maxPerConversationPerDay: 3, expirationMinutes: 60, riskLookbackDays: 30 };
    expect(settingsProblems(settings({ pixInConversation: null }))).toEqual([]);
    expect(settingsProblems(settings({ pixInConversation: pix }))).toEqual([]);
    expect(settingsProblems(settings({ pixInConversation: { ...pix, enabled: false, maxAmount: 999999 } }))).toEqual([]);
    expect(settingsProblems(settings({ pixInConversation: pix, requireIdentityVerification: false })).join()).toContain('identidade');
    expect(settingsProblems(settings({ pixInConversation: { ...pix, maxAmount: 50000.01 } })).join()).toContain('50.000');
    expect(settingsProblems(settings({ pixInConversation: { ...pix, maxPerConversationPerDay: 11 } })).join()).toContain('no dia');
    expect(settingsProblems(settings({ pixInConversation: { ...pix, expirationMinutes: 5 } })).join()).toContain('Validade');
    expect(settingsProblems(settings({ pixInConversation: { ...pix, riskLookbackDays: 0 } })).join()).toContain('Janela');
  });

  it('escopo e segregação da revisão (quem publicou a política não avalia)', () => {
    expect(scopeOf({ creditorId: null, portfolioId: null })).toBe('TENANT');
    expect(scopeOf({ creditorId: 'c-1', portfolioId: null })).toBe('CREDITOR');
    expect(scopeOf({ creditorId: 'c-1', portfolioId: 'p-1' })).toBe('PORTFOLIO');
    const policies = [{ id: 'pol-1', publishedBy: 'u-1' }];
    expect(reviewBlocked({ policyId: 'pol-1' }, policies, 'u-1')).toBe(true);
    expect(reviewBlocked({ policyId: 'pol-1' }, policies, 'u-2')).toBe(false);
    expect(reviewBlocked({ policyId: null }, policies, 'u-1')).toBe(false);
  });

  it('avaliação: nota, veredito e comentário obrigatório fora da política', () => {
    expect(reviewProblems({ score: 4, verdict: 'ADEQUATE', comment: '' })).toEqual([]);
    expect(reviewProblems({ score: null, verdict: null, comment: '' }).length).toBe(2);
    expect(reviewProblems({ score: 1, verdict: 'OUT_OF_POLICY', comment: ' ' }).join()).toContain('comentário');
  });

  it('séries de gráfico ordenadas com o valor em texto', () => {
    const b = bars({ DISPUTE: 2, HUMAN_REQUESTED: 6, OPT_OUT: 0 }, { DISPUTE: 'Contestação' });
    expect(b.map((x) => x.key)).toEqual(['HUMAN_REQUESTED', 'DISPUTE']);
    expect(b[0].share).toBe(1);
    expect(b[1].label).toBe('Contestação');
    expect(b[1].ofTotal).toBe(0.25);
    expect(bars(null)).toEqual([]);
    expect(fractionText(0.125)).toBe('12,5%');
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyJson('texto')).toBe('texto');
  });
});
