package br.com.gestorcobranca.negotiation.domain.model.policy;

import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.BreakRule;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Charges;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ConcessionLevel;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.DiscountTier;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.HonorariumBase;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Installments;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.InterestMode;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ScheduleFrequency;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Validity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Modelo de política com parâmetros usuais do mercado de cobrança de varejo/consumo, ponto de
 * partida para o cadastro (nenhuma política é criada automaticamente: as condições são do credor).
 * <ul>
 *   <li>juros de mora 1% a.m. simples pro rata die, multa 2% (CDC art. 52 §1º), sem correção;</li>
 *   <li>honorários de 10% sobre a dívida atualizada;</li>
 *   <li>desconto à vista até 100% de juros/multa, 50% de honorários e 20% do principal; até 6x,
 *       60% de juros/multa e 20% de honorários; até 12x, 30% de juros/multa;</li>
 *   <li>até 12 parcelas, mínimo de R$ 50, entrada mínima de 10%, sem juros de parcelamento;</li>
 *   <li>escada: 0 sem desconto; 1 multa e juros até metade do envelope; 2 juros, multa e
 *       honorários integrais do envelope; 3 limite da política, somente à vista;</li>
 *   <li>proposta válida por 5 dias; 1º pagamento em até 10 dias; quebra com 2 parcelas em atraso
 *       há mais de 5 dias.</li>
 * </ul>
 */
public final class StandardPolicyTerms {

    private StandardPolicyTerms() {
    }

    public static PolicyTerms marketDefault() {
        Charges charges = new Charges(new BigDecimal("1"), InterestMode.SIMPLE, new BigDecimal("2"), null, List.of(),
                new BigDecimal("10"), HonorariumBase.UPDATED_DEBT);
        List<DiscountTier> tiers = List.of(
                new DiscountTier(1, rates("20", "100", "100", "100", "100", "50")),
                new DiscountTier(6, rates("0", "60", "60", "60", "60", "20")),
                new DiscountTier(12, rates("0", "30", "30", "30", "30", "0")));
        Installments installments = new Installments(12, new BigDecimal("50"), new BigDecimal("10"), BigDecimal.ZERO,
                BigDecimal.ZERO, ScheduleFrequency.MONTHLY, null);
        List<ConcessionLevel> ladder = List.of(
                new ConcessionLevel(0, "Sem desconto", ComponentRates.NONE, null),
                new ConcessionLevel(1, "Multa e juros (metade do envelope)", rates("0", "50", "50", "50", "0", "0"), null),
                new ConcessionLevel(2, "Multa, juros e honorários", rates("0", "100", "100", "100", "100", "100"), null),
                new ConcessionLevel(3, "Limite da política (à vista)", ComponentRates.FULL, 1));
        PolicyRules base = new PolicyRules(charges, tiers, installments, new Validity(5, 10), ladder,
                new BreakRule(2, 5));
        return new PolicyTerms(base, List.of()).validate();
    }

    private static ComponentRates rates(String principal, String correction, String interest, String fine, String fees,
                                        String honorarium) {
        return new ComponentRates(new BigDecimal(principal), new BigDecimal(correction), new BigDecimal(interest),
                new BigDecimal(fine), new BigDecimal(fees), new BigDecimal(honorarium));
    }
}
