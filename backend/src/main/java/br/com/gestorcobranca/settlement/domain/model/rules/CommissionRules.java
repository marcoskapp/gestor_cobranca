package br.com.gestorcobranca.settlement.domain.model.rules;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Regras de comissionamento da assessoria num contrato de serviço (RB08, F17). Valor imutável,
 * persistido em jsonb com {@link #SCHEMA_VERSION}; validado em toda escrita e leitura.
 * <ul>
 *   <li>{@code defaultRate}: percentual sobre o valor recuperado quando nenhuma regra específica casa;</li>
 *   <li>{@code rateRules}: regras por carteira, faixa de atraso, tipo de recebimento (à vista ×
 *       parcelado), quem recebeu e renegociação; vence a de maior prioridade e, no empate, a mais
 *       específica (mais critérios preenchidos), depois o código;</li>
 *   <li>{@code fixedFeePerAgreement}: valor fixo por acordo, devido no primeiro recebimento do acordo
 *       (estornado se esse recebimento for estornado por inteiro, e devido de novo no próximo);</li>
 *   <li>{@code honorarium}: titularidade dos honorários cobrados do devedor e fração da assessoria;</li>
 *   <li>{@code reconciliationRequired}: só entra no demonstrativo o recebimento na conta da assessoria
 *       já conciliado (o recebido diretamente pelo credor não depende de conciliação).</li>
 * </ul>
 */
public record CommissionRules(BigDecimal defaultRate, List<RateRule> rateRules, BigDecimal fixedFeePerAgreement,
                              HonorariumPolicy honorarium, boolean reconciliationRequired) {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_RULE_CODE = "PADRAO";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public CommissionRules {
        rateRules = rateRules == null ? List.of() : List.copyOf(rateRules);
        honorarium = honorarium == null ? HonorariumPolicy.agencyFull() : honorarium;
    }

    /** Valida o conjunto (422 com código estável). */
    public CommissionRules validated() {
        requireRate(defaultRate, "commission_rules.default_rate_invalid");
        Guard.require(fixedFeePerAgreement == null || (fixedFeePerAgreement.signum() >= 0
                        && fixedFeePerAgreement.scale() <= 2), "commission_rules.fixed_fee_invalid",
                "Valor fixo por acordo deve ser positivo, com até 2 casas decimais");
        honorarium.validate();
        Guard.require(rateRules.size() <= 100, "commission_rules.too_many_rules", "No máximo 100 regras específicas");
        Set<String> codes = new HashSet<>();
        for (RateRule rule : rateRules) {
            rule.validate();
            Guard.require(codes.add(rule.code()), "commission_rules.duplicate_code",
                    "Código de regra repetido: " + rule.code());
        }
        return this;
    }

    /** Regra aplicável ao recebimento; vazio = percentual padrão. */
    public Optional<RateRule> match(ReceiptProfile profile) {
        return rateRules.stream().filter(r -> r.matches(profile))
                .min(Comparator.comparingInt(RateRule::priority).reversed()
                        .thenComparing(Comparator.comparingInt(RateRule::specificity).reversed())
                        .thenComparing(RateRule::code));
    }

    static void requireRate(BigDecimal rate, String code) {
        Guard.require(rate != null && rate.signum() >= 0 && rate.compareTo(HUNDRED) <= 0 && rate.scale() <= 4, code,
                "Percentual deve estar entre 0 e 100, com até 4 casas decimais");
    }

    /** Atributos do recebimento usados para escolher a regra. */
    public record ReceiptProfile(UUID portfolioId, Integer daysPastDue, PaymentType paymentType, ReceivedBy receivedBy,
                                 boolean renegotiation) {
    }

    /**
     * Regra específica: critérios nulos não restringem. {@code minDaysPastDue}/{@code maxDaysPastDue}
     * definem a faixa de atraso (inclusive); recebimento sem atraso conhecido não casa com regra que exige faixa.
     */
    public record RateRule(String code, String description, int priority, UUID portfolioId, Integer minDaysPastDue,
                           Integer maxDaysPastDue, PaymentType paymentType, ReceivedBy receivedBy, Boolean renegotiation,
                           BigDecimal rate) {

        void validate() {
            Guard.require(code != null && code.matches("[A-Z0-9_]{1,40}") && !DEFAULT_RULE_CODE.equals(code),
                    "commission_rules.code_invalid",
                    "Código da regra: letras maiúsculas, dígitos e _ (até 40), diferente de " + DEFAULT_RULE_CODE);
            Guard.require(description == null || description.length() <= 200, "commission_rules.description_too_long",
                    "Descrição da regra até 200 caracteres");
            Guard.require(priority >= 0 && priority <= 1000, "commission_rules.priority_invalid",
                    "Prioridade entre 0 e 1000");
            Guard.require(minDaysPastDue == null || minDaysPastDue >= 0, "commission_rules.band_invalid",
                    "Faixa de atraso inválida");
            Guard.require(maxDaysPastDue == null || maxDaysPastDue >= 0, "commission_rules.band_invalid",
                    "Faixa de atraso inválida");
            Guard.require(minDaysPastDue == null || maxDaysPastDue == null || maxDaysPastDue >= minDaysPastDue,
                    "commission_rules.band_invalid", "Fim da faixa de atraso anterior ao início");
            requireRate(rate, "commission_rules.rate_invalid");
            Guard.require(specificity() > 0, "commission_rules.rule_without_criteria",
                    "Regra específica " + code + " sem critério: use o percentual padrão");
        }

        boolean matches(ReceiptProfile p) {
            if (portfolioId != null && !portfolioId.equals(p.portfolioId())) {
                return false;
            }
            if (minDaysPastDue != null || maxDaysPastDue != null) {
                if (p.daysPastDue() == null) {
                    return false;
                }
                if (minDaysPastDue != null && p.daysPastDue() < minDaysPastDue) {
                    return false;
                }
                if (maxDaysPastDue != null && p.daysPastDue() > maxDaysPastDue) {
                    return false;
                }
            }
            if (paymentType != null && paymentType != p.paymentType()) {
                return false;
            }
            if (receivedBy != null && receivedBy != p.receivedBy()) {
                return false;
            }
            return renegotiation == null || renegotiation == p.renegotiation();
        }

        int specificity() {
            int n = 0;
            for (Object criterion : new ArrayList<>(java.util.Arrays.asList(portfolioId,
                    minDaysPastDue != null || maxDaysPastDue != null ? Boolean.TRUE : null, paymentType, receivedBy,
                    renegotiation))) {
                if (criterion != null) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * @param agencySharePercent fração dos honorários que pertence à assessoria quando
     *                           {@code ownership = AGENCY} (padrão 100%)
     */
    public record HonorariumPolicy(HonorariumOwnership ownership, BigDecimal agencySharePercent) {

        public static HonorariumPolicy agencyFull() {
            return new HonorariumPolicy(HonorariumOwnership.AGENCY, HUNDRED);
        }

        void validate() {
            Guard.require(ownership != null, "commission_rules.honorarium_ownership_required",
                    "Informe a quem pertencem os honorários");
            if (ownership == HonorariumOwnership.AGENCY) {
                requireRate(agencySharePercent, "commission_rules.honorarium_share_invalid");
            }
        }

        public BigDecimal effectiveAgencyShare() {
            return ownership == HonorariumOwnership.AGENCY ? agencySharePercent : BigDecimal.ZERO;
        }
    }
}
