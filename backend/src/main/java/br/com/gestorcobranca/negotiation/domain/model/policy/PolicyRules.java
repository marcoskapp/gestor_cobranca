package br.com.gestorcobranca.negotiation.domain.model.policy;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parâmetros de negociação de uma política (§1 do desenho), agrupados para que uma sobreposição
 * por carteira/faixa de atraso substitua grupos inteiros ({@link PolicyOverride}). Percentuais
 * de 0 a 100; taxas mensais em % a.m. (1 = 1% ao mês).
 *
 * @param charges       encargos (juros, multa, correção, encargos autorizados, honorários)
 * @param discountTiers desconto máximo por componente conforme a forma de pagamento
 * @param installments  regras de parcelamento
 * @param validity      validade da proposta e prazo da 1ª parcela
 * @param ladder        escada de concessões (níveis 0..n, contíguos)
 * @param breakRule     caracterização de quebra de acordo (usada na parte 2)
 */
@org.springframework.modulith.NamedInterface("usecases")
public record PolicyRules(
        Charges charges,
        List<DiscountTier> discountTiers,
        Installments installments,
        Validity validity,
        List<ConcessionLevel> ladder,
        BreakRule breakRule) {

    static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_MONTHLY_RATE = new BigDecimal("20");
    private static final BigDecimal MAX_FINE = new BigDecimal("20");
    private static final BigDecimal MAX_HONORARIUM = new BigDecimal("30");

    public PolicyRules {
        discountTiers = discountTiers == null ? List.of()
                : discountTiers.stream().sorted(Comparator.comparingInt(DiscountTier::upToInstallments)).toList();
        ladder = ladder == null ? List.of()
                : ladder.stream().sorted(Comparator.comparingInt(ConcessionLevel::level)).toList();
    }

    /** Valida todas as regras; lança {@code BusinessRuleException} com código {@code policy.terms.*}. */
    public PolicyRules validate() {
        Guard.require(charges != null, "policy.terms.charges_required", "Encargos obrigatórios");
        Guard.require(installments != null, "policy.terms.installments_required", "Regras de parcelamento obrigatórias");
        Guard.require(validity != null, "policy.terms.validity_required", "Validade obrigatória");
        Guard.require(breakRule != null, "policy.terms.break_rule_required", "Regra de quebra obrigatória");
        charges.validate();
        installments.validate();
        validity.validate();
        breakRule.validate();
        validateTiers(discountTiers);
        validateLadder(ladder, installments.maxInstallments());
        return this;
    }

    static void validateTiers(List<DiscountTier> tiers) {
        Set<Integer> seen = new HashSet<>();
        for (DiscountTier tier : tiers) {
            tier.validate();
            Guard.require(seen.add(tier.upToInstallments()), "policy.terms.discount_tier_duplicated",
                    "Faixas de desconto com o mesmo número de parcelas");
        }
    }

    static void validateLadder(List<ConcessionLevel> ladder, int maxInstallments) {
        Guard.require(!ladder.isEmpty(), "policy.terms.ladder_required", "A escada de concessões precisa de ao menos o nível 0");
        ConcessionLevel previous = null;
        for (int i = 0; i < ladder.size(); i++) {
            ConcessionLevel level = ladder.get(i);
            level.validate();
            Guard.require(level.level() == i, "policy.terms.ladder_not_contiguous",
                    "Níveis da escada devem ser contíguos a partir de 0");
            Guard.require(level.maxInstallments() == null || level.maxInstallments() <= maxInstallments,
                    "policy.terms.level_installments_exceeded", "Nível " + i + " permite mais parcelas que a política");
            if (previous != null) {
                Guard.require(previous.envelopeShare().notAbove(level.envelopeShare()), "policy.terms.ladder_not_ascending",
                        "Cada nível deve conceder ao menos o mesmo que o anterior, em todos os componentes");
            }
            previous = level;
        }
    }

    public int topLevel() {
        return ladder.size() - 1;
    }

    public Optional<ConcessionLevel> level(int level) {
        return level >= 0 && level < ladder.size() ? Optional.of(ladder.get(level)) : Optional.empty();
    }

    /** Desconto máximo da política por componente para {@code installmentCount} pagamentos (0 se nenhuma faixa cobrir). */
    public ComponentRates maxDiscountFor(int installmentCount) {
        return discountTiers.stream().filter(t -> t.upToInstallments() >= installmentCount).findFirst()
                .map(DiscountTier::maxDiscount).orElse(ComponentRates.NONE);
    }

    /** Aplica os grupos não nulos da sobreposição sobre estas regras. */
    public PolicyRules overriddenBy(PolicyOverride o) {
        return new PolicyRules(
                o.charges() != null ? o.charges() : charges,
                o.discountTiers() != null ? o.discountTiers() : discountTiers,
                o.installments() != null ? o.installments() : installments,
                o.validity() != null ? o.validity() : validity,
                o.ladder() != null ? o.ladder() : ladder,
                o.breakRule() != null ? o.breakRule() : breakRule);
    }

    public enum InterestMode {
        /** Juros simples: base × taxa × dias / 30. */
        SIMPLE,
        /** Juros compostos: base × ((1 + taxa)^(dias/30) − 1). */
        COMPOUND
    }

    public enum FeeType {
        /** Valor fixo por título vencido. */
        FIXED,
        /** Percentual sobre o principal em aberto do título vencido. */
        PERCENT_OF_PRINCIPAL
    }

    public enum HonorariumBase {
        /** Principal em aberto. */
        PRINCIPAL,
        /** Principal + correção monetária. */
        UPDATED_PRINCIPAL,
        /** Principal + correção + juros + multa + encargos (padrão de mercado). */
        UPDATED_DEBT
    }

    public enum ScheduleFrequency {
        /** Mesmo dia nos meses seguintes (ajustado ao último dia do mês quando necessário). */
        MONTHLY,
        /** A cada {@code intervalDays} dias corridos. */
        EVERY_N_DAYS
    }

    /**
     * @param interestMonthlyRate juros de mora (% a.m., pro rata die sobre mês comercial de 30 dias)
     * @param interestMode        simples ou compostos
     * @param finePercent         multa moratória (%), aplicada uma vez sobre a base corrigida
     * @param correctionIndexCode índice de correção monetária (tabela do tenant); nulo = sem correção
     * @param fees                encargos autorizados pelo credor
     * @param honorariumPercent   honorários (%) sobre {@code honorariumBase}
     */
    public record Charges(BigDecimal interestMonthlyRate, InterestMode interestMode, BigDecimal finePercent,
                          String correctionIndexCode, List<Fee> fees, BigDecimal honorariumPercent,
                          HonorariumBase honorariumBase) {

        public Charges {
            interestMonthlyRate = interestMonthlyRate == null ? BigDecimal.ZERO : interestMonthlyRate;
            finePercent = finePercent == null ? BigDecimal.ZERO : finePercent;
            honorariumPercent = honorariumPercent == null ? BigDecimal.ZERO : honorariumPercent;
            interestMode = interestMode == null ? InterestMode.SIMPLE : interestMode;
            honorariumBase = honorariumBase == null ? HonorariumBase.UPDATED_DEBT : honorariumBase;
            correctionIndexCode = correctionIndexCode == null || correctionIndexCode.isBlank() ? null
                    : correctionIndexCode.trim().toUpperCase();
            fees = fees == null ? List.of() : List.copyOf(fees);
        }

        void validate() {
            Guard.require(interestMonthlyRate.signum() >= 0 && interestMonthlyRate.compareTo(MAX_MONTHLY_RATE) <= 0,
                    "policy.terms.interest_invalid", "Juros de mora entre 0 e 20% a.m.");
            Guard.require(finePercent.signum() >= 0 && finePercent.compareTo(MAX_FINE) <= 0,
                    "policy.terms.fine_invalid", "Multa entre 0 e 20%");
            Guard.require(honorariumPercent.signum() >= 0 && honorariumPercent.compareTo(MAX_HONORARIUM) <= 0,
                    "policy.terms.honorarium_invalid", "Honorários entre 0 e 30%");
            Guard.require(correctionIndexCode == null || correctionIndexCode.matches("[A-Z0-9_]{2,20}"),
                    "policy.terms.index_code_invalid", "Código de índice inválido");
            Set<String> codes = new HashSet<>();
            for (Fee fee : fees) {
                fee.validate();
                Guard.require(codes.add(fee.code()), "policy.terms.fee_duplicated", "Encargo duplicado: " + fee.code());
            }
        }
    }

    /** Encargo autorizado pelo credor (ex.: tarifa de cobrança). */
    public record Fee(String code, String description, FeeType type, BigDecimal value) {

        void validate() {
            Guard.require(code != null && code.matches("[A-Z0-9_]{2,30}"), "policy.terms.fee_code_invalid",
                    "Código do encargo: 2 a 30 caracteres em maiúsculas, dígitos ou _");
            Guard.require(description != null && !description.isBlank() && description.length() <= 120,
                    "policy.terms.fee_description_invalid", "Descrição do encargo obrigatória (até 120 caracteres)");
            Guard.require(type != null, "policy.terms.fee_type_required", "Tipo do encargo obrigatório");
            Guard.require(value != null && value.signum() > 0, "policy.terms.fee_value_invalid", "Valor do encargo positivo");
            Guard.require(type != FeeType.PERCENT_OF_PRINCIPAL || value.compareTo(HUNDRED) <= 0,
                    "policy.terms.fee_value_invalid", "Percentual do encargo até 100");
        }
    }

    /**
     * Desconto máximo por componente para pagamentos em até {@code upToInstallments} vezes
     * (1 = à vista). A forma de pagamento usa a primeira faixa que a cobre; acima da maior faixa
     * não há desconto.
     */
    public record DiscountTier(int upToInstallments, ComponentRates maxDiscount) {

        void validate() {
            Guard.require(upToInstallments >= 1 && upToInstallments <= 360, "policy.terms.discount_tier_invalid",
                    "Faixa de desconto: 1 a 360 parcelas");
            Guard.require(maxDiscount != null, "policy.terms.discount_tier_invalid", "Descontos da faixa obrigatórios");
            maxDiscount.validate("policy.terms.discount_tier_invalid", "Faixa até " + upToInstallments + " parcelas");
        }
    }

    /**
     * Parcelamento. A contagem de parcelas inclui a entrada (à vista = 1).
     *
     * @param maxInstallments       número máximo de pagamentos
     * @param minInstallmentAmount  valor mínimo de cada parcela
     * @param minDownPaymentPercent entrada mínima (% do valor negociado)
     * @param minDownPaymentAmount  entrada mínima (R$); vale o maior entre % e valor
     * @param monthlyInterestRate   juros do parcelamento (% a.m., tabela Price); 0 = sem juros
     * @param frequency             periodicidade dos vencimentos
     * @param intervalDays          intervalo em dias (somente {@code EVERY_N_DAYS})
     */
    public record Installments(int maxInstallments, BigDecimal minInstallmentAmount, BigDecimal minDownPaymentPercent,
                               BigDecimal minDownPaymentAmount, BigDecimal monthlyInterestRate,
                               ScheduleFrequency frequency, Integer intervalDays) {

        public Installments {
            minInstallmentAmount = minInstallmentAmount == null ? BigDecimal.ZERO : minInstallmentAmount;
            minDownPaymentPercent = minDownPaymentPercent == null ? BigDecimal.ZERO : minDownPaymentPercent;
            minDownPaymentAmount = minDownPaymentAmount == null ? BigDecimal.ZERO : minDownPaymentAmount;
            monthlyInterestRate = monthlyInterestRate == null ? BigDecimal.ZERO : monthlyInterestRate;
            frequency = frequency == null ? ScheduleFrequency.MONTHLY : frequency;
        }

        void validate() {
            Guard.require(maxInstallments >= 1 && maxInstallments <= 360, "policy.terms.max_installments_invalid",
                    "Parcelas máximas entre 1 e 360");
            Guard.require(minInstallmentAmount.signum() >= 0, "policy.terms.min_installment_invalid",
                    "Valor mínimo de parcela não pode ser negativo");
            Guard.require(minDownPaymentPercent.signum() >= 0 && minDownPaymentPercent.compareTo(HUNDRED) < 0,
                    "policy.terms.min_down_payment_invalid", "Entrada mínima entre 0 e 100% (exclusive)");
            Guard.require(minDownPaymentAmount.signum() >= 0, "policy.terms.min_down_payment_invalid",
                    "Entrada mínima não pode ser negativa");
            Guard.require(monthlyInterestRate.signum() >= 0 && monthlyInterestRate.compareTo(MAX_MONTHLY_RATE) <= 0,
                    "policy.terms.installment_interest_invalid", "Juros do parcelamento entre 0 e 20% a.m.");
            if (frequency == ScheduleFrequency.EVERY_N_DAYS) {
                Guard.require(intervalDays != null && intervalDays >= 7 && intervalDays <= 90,
                        "policy.terms.interval_invalid", "Intervalo entre vencimentos de 7 a 90 dias");
            } else {
                Guard.require(intervalDays == null, "policy.terms.interval_invalid",
                        "Intervalo em dias só se aplica à periodicidade EVERY_N_DAYS");
            }
        }
    }

    /**
     * @param proposalValidityDays  dias de validade de uma proposta
     * @param maxDaysToFirstPayment prazo máximo, a partir de hoje, para o 1º pagamento (à vista ou entrada)
     */
    public record Validity(int proposalValidityDays, int maxDaysToFirstPayment) {

        void validate() {
            Guard.require(proposalValidityDays >= 1 && proposalValidityDays <= 90, "policy.terms.validity_invalid",
                    "Validade da proposta entre 1 e 90 dias");
            Guard.require(maxDaysToFirstPayment >= 0 && maxDaysToFirstPayment <= 90,
                    "policy.terms.first_payment_invalid", "Prazo do 1º pagamento entre 0 e 90 dias");
        }
    }

    /**
     * Nível da escada de concessões.
     *
     * @param envelopeShare   fatia (%) do desconto máximo da política liberada neste nível, por componente
     * @param maxInstallments limite de parcelas no nível (nulo = o da política; 1 = somente à vista)
     */
    public record ConcessionLevel(int level, String name, ComponentRates envelopeShare, Integer maxInstallments) {

        void validate() {
            Guard.require(name != null && !name.isBlank() && name.length() <= 80, "policy.terms.level_name_invalid",
                    "Nome do nível obrigatório (até 80 caracteres)");
            Guard.require(envelopeShare != null, "policy.terms.level_share_required", "Fatia do envelope obrigatória");
            envelopeShare.validate("policy.terms.level_share_invalid", "Nível " + level);
            Guard.require(maxInstallments == null || maxInstallments >= 1, "policy.terms.level_installments_invalid",
                    "Parcelas do nível devem ser ao menos 1");
        }
    }

    /**
     * Quebra de acordo (RB05): {@code overdueInstallments} parcelas vencidas há mais de
     * {@code toleranceDays} dias caracterizam quebra.
     */
    public record BreakRule(int overdueInstallments, int toleranceDays) {

        void validate() {
            Guard.require(overdueInstallments >= 1 && overdueInstallments <= 12, "policy.terms.break_rule_invalid",
                    "Parcelas em atraso para quebra entre 1 e 12");
            Guard.require(toleranceDays >= 0 && toleranceDays <= 180, "policy.terms.break_rule_invalid",
                    "Tolerância entre 0 e 180 dias");
        }
    }
}
