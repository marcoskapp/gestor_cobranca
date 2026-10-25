import { normalizeRules, standardRules, validateRules } from './plan-rules';

describe('regras do plano (cliente)', () => {
  it('o modelo de referência de mercado é válido', () => {
    expect(validateRules(standardRules())).toEqual([]);
  });

  it('exige componente remunerável e recusa o que o servidor recusa', () => {
    const r = standardRules();
    r.recoveryRate = null;
    r.teamPool = null;
    expect(validateRules(r)[0]).toContain('ao menos um componente');

    const tiers = standardRules();
    tiers.recoveryRate!.tables[0].tiers = [{ fromAmount: 10, ratePercent: 1 }, { fromAmount: 10, ratePercent: 120 }];
    const issues = validateRules(tiers);
    expect(issues).toContain('Tabela 1: a primeira faixa começa em R$ 0,00.');
    expect(issues).toContain('Tabela 1: taxa de cada faixa entre 0% e 100%.');
    expect(issues).toContain('Tabela 1: as faixas precisam ter inícios diferentes.');

    const goal = standardRules();
    goal.goal!.metric = 'RECOVERED_VS_EXPECTED';
    goal.goal!.acceleratorThresholdPercent = 50;
    goal.goal!.belowTriggerFactor = 1.5;
    expect(validateRules(goal)).toEqual(
      expect.arrayContaining([
        'Meta: recuperado × esperado ainda não é apurado pelo motor de comissão.',
        'Meta: fator abaixo do gatilho entre 0 e 1.',
        'Meta: o limiar do acelerador deve ser maior ou igual ao gatilho.',
      ]),
    );

    const attribution = standardRules();
    attribution.attribution.originWeights.ASSISTANT_AUTONOMOUS = 10;
    attribution.clearing.graceBusinessDays = 61;
    attribution.eligibility.roleCodes = ['x'];
    expect(validateRules(attribution)).toEqual(
      expect.arrayContaining([
        'Atribuição: acordo fechado sozinho pelo assistente tem peso 0% (vai para o pool da equipe).',
        'Carência: entre 0 e 60 dias úteis.',
        'Elegibilidade: códigos de perfil com 2 a 40 letras maiúsculas, números ou _.',
      ]),
    );
  });

  it('normaliza antes de enviar: perfis em maiúsculas sem repetição, faixas ordenadas e vazios como nulos', () => {
    const r = standardRules();
    r.eligibility.roleCodes = [' operador', 'OPERADOR', ''];
    r.recoveryRate!.tables[0].tiers.reverse();
    r.limits.periodCap = '' as unknown as null;
    r.difficultyMultipliers[0].segment.delayBuckets.push('D0_30');
    const n = normalizeRules(r);
    expect(n.eligibility.roleCodes).toEqual(['OPERADOR']);
    expect(n.recoveryRate!.tables[0].tiers.map((t) => t.fromAmount)).toEqual([0, 50000, 100000]);
    expect(n.limits.periodCap).toBeNull();
    expect(n.difficultyMultipliers[0].segment.delayBuckets).toEqual(['D0_30', 'D31_90']);
    expect(r.eligibility.roleCodes).toHaveLength(3);
  });
});
