import { CampaignSettings } from './telephony.models';
import { abandonLevel, awaitsTabulation, campaignProblems, clockText, durationText, isCallActive, isDenied, presenceActions, tabulationDeadline } from './telephony.rules';

const settings = (o: Partial<CampaignSettings> = {}): CampaignSettings => ({
  name: 'Varejo 30-60',
  portfolioId: null,
  includeAssigned: false,
  mode: 'PREDICTIVE',
  maxDialRatio: 2,
  maxAbandonRate: 3,
  maxAttempts: 3,
  retryIntervalMinutes: 120,
  ringTimeoutSeconds: 25,
  windowStart: null,
  windowEnd: null,
  ...o,
});

describe('regras da telefonia', () => {
  it('presença: pausa e saída só disponível/pausado; pós-atendimento exige tabular', () => {
    expect(presenceActions(null).login).toBe(false);
    expect(presenceActions({ status: 'OFFLINE', active: true }).login).toBe(true);
    expect(presenceActions({ status: 'OFFLINE', active: false }).login).toBe(false);
    expect(presenceActions({ status: 'AVAILABLE', active: true })).toMatchObject({ pause: true, logout: true, available: false, blocked: null });
    expect(presenceActions({ status: 'PAUSED', active: true })).toMatchObject({ pause: false, available: true, logout: true });
    expect(presenceActions({ status: 'WRAP_UP', active: true })).toMatchObject({ pause: false, logout: false, available: false });
    expect(presenceActions({ status: 'WRAP_UP', active: true }).blocked).toContain('Tabule');
    expect(presenceActions({ status: 'ON_CALL', active: true }).blocked).toContain('Em chamada');
  });

  it('chamada: em andamento, negada pelo porteiro, aguardando tabulação', () => {
    expect(isCallActive({ status: 'RINGING' })).toBe(true);
    expect(isCallActive({ status: 'COMPLETED' })).toBe(false);
    expect(isDenied({ status: 'FAILED', failureCode: 'CONTACT_DENIED' })).toBe(true);
    expect(isDenied({ status: 'FAILED', failureCode: 'provider' })).toBe(false);
    expect(awaitsTabulation({ status: 'COMPLETED', interactionState: 'AWAITING_TABULATION' })).toBe(true);
    expect(awaitsTabulation({ status: 'ANSWERED', interactionState: 'AWAITING_TABULATION' })).toBe(false);
  });

  it('prazo da tabulação automática (15 min depois do fim) e contagem regressiva', () => {
    expect(tabulationDeadline({ endedAt: null })).toBeNull();
    expect(tabulationDeadline({ endedAt: '2026-10-24T12:00:00Z' })?.toISOString()).toBe('2026-10-24T12:15:00.000Z');
    expect(clockText(14 * 60_000 + 5_000)).toBe('14:05');
    expect(clockText(-1)).toBe('00:00');
    expect(clockText(3_725_000)).toBe('1:02:05');
    expect(durationText(125)).toBe('2 min 05 s');
    expect(durationText(null)).toBe('—');
  });

  it('parâmetros da campanha como o domínio', () => {
    expect(campaignProblems(settings(), 'VAREJO-30')).toEqual([]);
    expect(campaignProblems(settings(), 'x').join()).toContain('Código');
    expect(campaignProblems(settings({ maxDialRatio: 6 })).join()).toContain('Razão');
    // progressivo ignora a razão
    expect(campaignProblems(settings({ mode: 'PROGRESSIVE', maxDialRatio: 6 }))).toEqual([]);
    expect(campaignProblems(settings({ maxAbandonRate: 12 })).join()).toContain('Abandono');
    expect(campaignProblems(settings({ maxAttempts: 0 })).join()).toContain('Tentativas');
    expect(campaignProblems(settings({ retryIntervalMinutes: 2 })).join()).toContain('Intervalo');
    expect(campaignProblems(settings({ ringTimeoutSeconds: 90 })).join()).toContain('toque');
    expect(campaignProblems(settings({ windowStart: '08:00:00' })).join()).toContain('início e fim');
    expect(campaignProblems(settings({ windowStart: '20:00:00', windowEnd: '08:00:00' })).join()).toContain('antes do fim');
  });

  it('nível do abandono em relação ao máximo', () => {
    expect(abandonLevel({ abandonRate: 1, maxAbandonRate: 3 })).toBe('ok');
    expect(abandonLevel({ abandonRate: 2.5, maxAbandonRate: 3 })).toBe('near');
    expect(abandonLevel({ abandonRate: 3, maxAbandonRate: 3 })).toBe('limit');
    expect(abandonLevel({ abandonRate: null, maxAbandonRate: 3 })).toBe('ok');
  });
});
