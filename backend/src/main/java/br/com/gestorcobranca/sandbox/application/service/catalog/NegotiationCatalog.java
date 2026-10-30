package br.com.gestorcobranca.sandbox.application.service.catalog;

import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyOverride;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.BreakRule;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Charges;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ConcessionLevel;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.DiscountTier;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.HonorariumBase;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Installments;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.InterestMode;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ScheduleFrequency;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Validity;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Segment;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.HonorariumPolicy;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.RateRule;
import br.com.gestorcobranca.settlement.domain.model.rules.HonorariumOwnership;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Políticas de cobrança/negociação e regras de comissionamento de demonstração, por segmento do credor. Valores
 * plausíveis de mercado para a demonstração — não são recomendação comercial. Os termos passam pela mesma validação
 * do cadastro pela tela ({@code PolicyTerms#validate}, {@code CommissionRules#validated}).
 */
public final class NegotiationCatalog {

    private NegotiationCatalog() {
    }

    /** Termos da política do credor: juros/multa/honorários, desconto máximo, entrada mínima, parcelas e escada. */
    public static PolicyTerms policy(Segment segment) {
        return switch (segment) {
            case BANK -> new PolicyTerms(rules(charges("1", "2", "10"),
                    List.of(tier(1, "20", "100", "50"), tier(6, "0", "60", "20"), tier(12, "0", "30", "0")),
                    installments(12, "50", "10"), new Validity(5, 10), new BreakRule(2, 5)),
                    // atraso longo (acima de 1 ano): mais desconto à vista sobre o principal
                    List.of(new PolicyOverride("ATRASO_LONGO", null, 361, null, null,
                            List.of(tier(1, "40", "100", "100"), tier(6, "10", "80", "50"), tier(12, "0", "50", "20")),
                            null, null, null, null)));
            case RETAIL -> new PolicyTerms(rules(charges("2", "2", "10"),
                    List.of(tier(1, "30", "100", "100"), tier(4, "10", "80", "50"), tier(10, "0", "40", "0")),
                    installments(10, "40", "15"), new Validity(3, 7), new BreakRule(2, 5)), List.of());
            case SERVICES -> new PolicyTerms(rules(charges("1", "2", "10"),
                    List.of(tier(1, "10", "100", "50"), tier(3, "0", "50", "0"), tier(6, "0", "20", "0")),
                    installments(6, "30", "20"), new Validity(5, 10), new BreakRule(1, 10)), List.of());
        };
    }

    /**
     * Comissão da assessoria no contrato: percentual padrão, faixas por atraso e à vista, valor fixo por acordo e
     * honorários da assessoria; só entra no demonstrativo o recebimento conciliado.
     */
    public static CommissionRules commission(Segment segment) {
        return switch (segment) {
            case BANK -> new CommissionRules(new BigDecimal("15"), List.of(
                    new RateRule("ATRASO_ACIMA_360", "Atraso acima de 360 dias", 20, null, 361, null, null, null, null,
                            new BigDecimal("25")),
                    new RateRule("A_VISTA", "Recebimento à vista", 10, null, null, null, PaymentType.LUMP_SUM, null, null,
                            new BigDecimal("12")),
                    new RateRule("RENEGOCIACAO", "Renegociação de acordo", 5, null, null, null, null, null, true,
                            new BigDecimal("10"))),
                    new BigDecimal("15.00"), new HonorariumPolicy(HonorariumOwnership.AGENCY, new BigDecimal("100")), true);
            case RETAIL -> new CommissionRules(new BigDecimal("18"), List.of(
                    new RateRule("ATRASO_ACIMA_180", "Atraso acima de 180 dias", 20, null, 181, null, null, null, null,
                            new BigDecimal("22"))),
                    null, new HonorariumPolicy(HonorariumOwnership.AGENCY, new BigDecimal("50")), true);
            case SERVICES -> new CommissionRules(new BigDecimal("20"), List.of(
                    new RateRule("PARCELADO", "Recebimento parcelado", 10, null, null, null, PaymentType.INSTALLMENTS,
                            null, null, new BigDecimal("16"))),
                    new BigDecimal("5.00"), new HonorariumPolicy(HonorariumOwnership.CREDITOR, null), true);
        };
    }

    private static PolicyRules rules(Charges charges, List<DiscountTier> tiers, Installments installments,
                                     Validity validity, BreakRule breakRule) {
        int max = installments.maxInstallments();
        List<ConcessionLevel> ladder = List.of(
                new ConcessionLevel(0, "Sem desconto", ComponentRates.NONE, null),
                new ConcessionLevel(1, "Multa e juros (metade do envelope)", rates("0", "50", "50", "50", "0", "0"), null),
                new ConcessionLevel(2, "Multa, juros e honorários", rates("0", "100", "100", "100", "100", "100"), max),
                new ConcessionLevel(3, "Limite da política (à vista)", ComponentRates.FULL, 1));
        return new PolicyRules(charges, tiers, installments, validity, ladder, breakRule);
    }

    private static Charges charges(String interest, String fine, String honorarium) {
        return new Charges(new BigDecimal(interest), InterestMode.SIMPLE, new BigDecimal(fine), null, List.of(),
                new BigDecimal(honorarium), HonorariumBase.UPDATED_DEBT);
    }

    private static Installments installments(int max, String minInstallment, String minDownPaymentPercent) {
        return new Installments(max, new BigDecimal(minInstallment), new BigDecimal(minDownPaymentPercent),
                BigDecimal.ZERO, BigDecimal.ZERO, ScheduleFrequency.MONTHLY, null);
    }

    /** Desconto máximo: principal, encargos (correção, juros, multa, tarifas) e honorários. */
    private static DiscountTier tier(int upTo, String principal, String charges, String honorarium) {
        return new DiscountTier(upTo, rates(principal, charges, charges, charges, charges, honorarium));
    }

    private static ComponentRates rates(String principal, String correction, String interest, String fine,
                                        String fees, String honorarium) {
        return new ComponentRates(new BigDecimal(principal), new BigDecimal(correction), new BigDecimal(interest),
                new BigDecimal(fine), new BigDecimal(fees), new BigDecimal(honorarium));
    }
}
