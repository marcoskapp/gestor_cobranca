import { answeredByCurrentOwner } from './conversation-panel';

describe('"Avaliar atendimento" na conversa', () => {
  const base = { ownerType: 'USER' as const, ownerUserId: 'u-2', ownerSince: '2026-11-07T14:00:00Z', lastOutboundAt: '2026-11-07T14:05:00Z' };

  it('só quando a última resposta é do dono atual', () => {
    expect(answeredByCurrentOwner(base)).toBe(true);
    // transferida depois da última resposta: quem respondeu foi o dono anterior
    expect(answeredByCurrentOwner({ ...base, ownerSince: '2026-11-07T15:00:00Z' })).toBe(false);
    expect(answeredByCurrentOwner({ ...base, lastOutboundAt: null })).toBe(false);
    expect(answeredByCurrentOwner({ ...base, ownerSince: null })).toBe(false);
    expect(answeredByCurrentOwner({ ...base, ownerType: 'ASSISTANT' as never })).toBe(false);
  });
});
