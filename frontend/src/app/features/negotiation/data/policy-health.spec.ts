import { SegmentHealth, share, suggestionText } from './policy-health';

const segment = (overrides: Partial<SegmentHealth> = {}): SegmentHealth => ({
  portfolioId: 'p-1',
  delayBucket: 'D361_PLUS',
  agreements: 30,
  matureAgreements: 20,
  brokenEarly: 8,
  earlyBreakRate: 0.4,
  agreedAmount: 30000,
  paidAmount: 12000,
  paidOverAgreed: 0.4,
  averageEnvelopeUsage: 92.5,
  highEnvelopeShare: 0.7,
  portalSimulations: 50,
  portalWithoutOffer: 25,
  portalWithoutOfferShare: 0.5,
  cannotAfford: 12,
  ...overrides,
});

describe('saúde da política: texto das sugestões', () => {
  it('mostra a evidência do recorte e compara com o resto do credor, sem mandar mudar a política', () => {
    const s = segment();
    const total = segment({ matureAgreements: 120, brokenEarly: 18 });
    const t = suggestionText('HIGH_EARLY_BREAK', s, total);
    expect(t.title).toContain('Quebra alta');
    // resto do credor: (18 − 8) ÷ (120 − 20) = 10%
    expect(t.evidence).toBe('8 de 20 acordos maduros quebraram em até 60 dias: 40% (no resto do credor: 10%).');
    expect(t.action).toMatch(/^Avalie/);
  });

  it('sem resto do credor com acordos maduros, não inventa a comparação', () => {
    const s = segment();
    expect(suggestionText('HIGH_EARLY_BREAK', s, s).evidence).not.toContain('resto do credor');
  });

  it('portal e orçamento usam as contagens do portal; percentuais com vírgula', () => {
    expect(suggestionText('PORTAL_WITHOUT_OFFER', segment(), segment()).evidence).toContain('25 de 50 consultas');
    expect(suggestionText('CANNOT_AFFORD', segment(), segment()).evidence).toContain('12 pedidos');
    expect(suggestionText('DISCOUNT_UNUSED', segment({ averageEnvelopeUsage: 12.5, highEnvelopeShare: 0.05 }), segment()).evidence).toContain('12,5%');
    expect(share(0.125)).toBe('12,5%');
    expect(share(null)).toBe('—');
  });
});
