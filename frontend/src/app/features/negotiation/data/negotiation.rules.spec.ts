import { marketTemplate, offer, simulationResult } from '../../../../testing/negotiation-fixtures';
import { competenceText, nextCompetence, ratesFromRows } from '../indexes/index-list';
import { levelText } from '../authority/authority-page';
import { overrideScope, percent, planText, ratesText } from './negotiation.labels';
import { PolicyOverride } from './negotiation.models';
import {
  cloneTerms,
  justificationProblem,
  offeredLevels,
  overriddenGroups,
  overriddenRules,
  overridesConflict,
  savingShare,
  sideBySide,
  specificity,
  termsProblems,
} from './negotiation.rules';

const override = (overrides: Partial<PolicyOverride>): PolicyOverride => ({
  code: 'LONGO_ATRASO',
  portfolioId: null,
  minDaysOverdue: 181,
  maxDaysOverdue: null,
  charges: null,
  discountTiers: null,
  installments: null,
  validity: null,
  ladder: null,
  breakRule: null,
  ...overrides,
});

describe('regras de tela do módulo negotiation', () => {
  describe('termos da política', () => {
    it('o modelo de mercado é válido', () => {
      expect(termsProblems(marketTemplate())).toEqual([]);
    });

    it('limites dos encargos, parcelamento, validade e quebra', () => {
      const t = marketTemplate();
      t.base.charges.finePercent = 25;
      t.base.installments.frequency = 'EVERY_N_DAYS';
      t.base.validity.proposalValidityDays = 0;
      t.base.breakRule.overdueInstallments = 0;
      expect(termsProblems(t)).toEqual([
        'Multa entre 0 e 20%.',
        'Intervalo entre vencimentos de 7 a 90 dias.',
        'Validade da proposta entre 1 e 90 dias.',
        'Parcelas em atraso para quebra entre 1 e 12.',
      ]);
    });

    it('escada contígua, crescente e dentro das parcelas da política; faixas sem repetição', () => {
      const t = marketTemplate();
      t.base.ladder[2].envelopeShare.interest = 10;
      t.base.ladder[3].maxInstallments = 24;
      t.base.discountTiers[1].upToInstallments = 1;
      const problems = termsProblems(t);
      expect(problems).toContain('Duas faixas de desconto para 1 parcela(s).');
      expect(problems).toContain('Nível 2 concede menos que o nível 1 em algum componente.');
      expect(problems).toContain('Nível 3: parcelas entre 1 e 12 (limite da política).');
      const gap = marketTemplate();
      gap.base.ladder.splice(1, 1);
      expect(termsProblems(gap)).toContain('Níveis da escada devem ser contíguos a partir de 0.');
    });

    it('sobreposições: seletor, grupos, combinação válida e interseção de faixas', () => {
      const t = marketTemplate();
      t.overrides.push(override({ code: 'x', minDaysOverdue: null }));
      expect(termsProblems(t)).toEqual(
        expect.arrayContaining([
          'Sobreposição x: código com 2 a 40 maiúsculas, dígitos ou _.',
          'Sobreposição x: informe a carteira ou a faixa de atraso.',
          expect.stringContaining('Sobreposição x: substitua ao menos um grupo'),
        ]),
      );
      const ok = marketTemplate();
      const validity = { proposalValidityDays: 10, maxDaysToFirstPayment: 15 };
      ok.overrides.push(override({ validity }), override({ code: 'MEDIO', minDaysOverdue: 91, maxDaysOverdue: 180, validity }));
      expect(termsProblems(ok)).toEqual([]);
      ok.overrides[1].maxDaysOverdue = 200;
      expect(termsProblems(ok)).toEqual(['Sobreposição MEDIO: intercepta a sobreposição LONGO_ATRASO.']);
    });

    it('especificidade, conflito e regras resultantes', () => {
      const band = override({});
      const portfolio = override({ code: 'CARTEIRA', portfolioId: 'p-1', minDaysOverdue: null });
      const both = override({ code: 'AMBOS', portfolioId: 'p-1' });
      expect([specificity(band), specificity(portfolio), specificity(both)]).toEqual([1, 2, 3]);
      expect(overridesConflict(band, portfolio)).toBe(false);
      expect(overridesConflict(portfolio, override({ code: 'OUTRA', portfolioId: 'p-1', minDaysOverdue: null }))).toBe(true);
      expect(overridesConflict(portfolio, override({ code: 'OUTRA', portfolioId: 'p-2', minDaysOverdue: null }))).toBe(false);
      const base = marketTemplate().base;
      const rules = overriddenRules(base, override({ breakRule: { overdueInstallments: 3, toleranceDays: 10 } }));
      expect(rules.breakRule.overdueInstallments).toBe(3);
      expect(rules.charges).toBe(base.charges);
      expect(overriddenGroups(override({ ladder: [], validity: base.validity }))).toEqual(['validity', 'ladder']);
      expect(overrideScope(both, () => 'Cartões')).toBe('Cartões · a partir de 181 dias de atraso');
      expect(overrideScope(override({ minDaysOverdue: 91, maxDaysOverdue: 180 }), () => '')).toBe('91 a 180 dias de atraso');
    });

    it('cópia profunda dos termos', () => {
      const t = marketTemplate();
      const copy = cloneTerms(t);
      copy.base.ladder[0].name = 'outro';
      expect(t.base.ladder[0].name).toBe('Sem desconto');
    });
  });

  describe('simulação', () => {
    it('à vista × maior parcelamento do nível, lado a lado', () => {
      const result = simulationResult({
        offers: [offer(), offer({ installments: 3 }), offer({ installments: 6 }), offer({ level: 1, levelName: 'Multa e juros', installments: 1 })],
      });
      const pair = sideBySide(result, 0);
      expect(pair.cash?.installments).toBe(1);
      expect(pair.installment?.installments).toBe(6);
      expect(sideBySide(result, 1)).toMatchObject({ levelName: 'Multa e juros', installment: null });
      expect(offeredLevels(result)).toEqual([0, 1]);
    });

    it('justificativa obrigatória acima do nível 0 e descrição para "outro motivo"', () => {
      expect(justificationProblem(0, null, '')).toBeNull();
      expect(justificationProblem(1, null, '')).toContain('informe o motivo');
      expect(justificationProblem(2, 'OTHER', ' ')).toBe('Descreva o motivo da concessão.');
      expect(justificationProblem(2, 'INCOME_LOSS', '')).toBeNull();
    });

    it('textos de oferta, percentuais e economia', () => {
      expect(planText({ installments: 1, downPayment: null })).toBe('À vista');
      expect(planText({ installments: 6, downPayment: 100 })).toBe('Entrada + 5 parcelas');
      expect(planText({ installments: 4, downPayment: null })).toBe('4 parcelas');
      expect(percent(12.5)).toBe('12,5%');
      expect(ratesText({ principal: 0, correction: 0, interest: 100, fine: 50, fees: 0, honorarium: 0 })).toBe('Juros 100% · Multa 50%');
      expect(ratesText({ principal: 0, correction: 0, interest: 0, fine: 0, fees: 0, honorarium: 0 })).toBe('Sem desconto');
      expect(savingShare({ discountTotal: 70 }, 1177)).toBe(5.95);
      expect(levelText(99)).toBe('Topo da escada');
      expect(levelText(2)).toBe('Nível 2');
    });
  });

  describe('índices', () => {
    it('competências e variações digitadas', () => {
      expect(competenceText('2026-09')).toBe('09/2026');
      expect(nextCompetence('2026-12')).toBe('2027-01');
      expect(nextCompetence('2026-03')).toBe('2026-04');
      expect(ratesFromRows([{ competence: '2026-09', ratePercent: 0.44 }, { competence: '2026-10', ratePercent: -0.1 }])).toEqual({
        rates: [
          { competence: '2026-09', ratePercent: 0.44 },
          { competence: '2026-10', ratePercent: -0.1 },
        ],
        problems: [],
      });
      expect(ratesFromRows([{ competence: '2026-13', ratePercent: 1 }, { competence: '2026-09', ratePercent: null }]).problems).toEqual([
        'Competência inválida: 2026-13.',
        'Informe a variação de 09/2026.',
      ]);
      expect(ratesFromRows([{ competence: '2026-09', ratePercent: 1 }, { competence: '2026-09', ratePercent: 2 }]).problems).toEqual([
        'Competência repetida: 09/2026.',
      ]);
    });
  });
});
