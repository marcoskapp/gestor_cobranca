package br.com.gestorcobranca.incentive.domain.model.plan;

import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Regras tipadas de uma versão de plano de comissão (§4). Persistidas em JSON com
 * {@link #SCHEMA_VERSION}; toda gravação passa por {@link #validate()}. Percentuais são
 * expressos de 0 a 100 (1,5 = 1,5%); fatores são multiplicadores (1,2 = +20%).
 *
 * @param recoveryRate         percentual sobre o recuperado (opcional)
 * @param fixedPerAgreement    valor fixo por acordo com 1ª parcela paga (opcional)
 * @param goal                 meta com gatilho, acelerador e teto (opcional)
 * @param difficultyMultipliers multiplicadores por segmento (vazio = 1,0 para todos)
 * @param teamPool             pool de equipe para supervisores (opcional)
 */
public record PlanRules(
        Eligibility eligibility,
        RecoveryRateComponent recoveryRate,
        FixedPerAgreementComponent fixedPerAgreement,
        GoalComponent goal,
        List<DifficultyMultiplier> difficultyMultipliers,
        QualityModifiers quality,
        AttributionRules attribution,
        TeamPoolComponent teamPool,
        Limits limits,
        ClearingRules clearing) {

    /** Versão do esquema JSON das regras. Mudanças incompatíveis exigem nova versão e conversão. */
    public static final int SCHEMA_VERSION = 1;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_FACTOR = new BigDecimal("10");

    public PlanRules {
        difficultyMultipliers = difficultyMultipliers == null ? List.of() : List.copyOf(difficultyMultipliers);
    }

    /** Valida todas as regras; lança {@code BusinessRuleException} com código {@code plan.rules.*}. */
    public PlanRules validate() {
        Guard.require(eligibility != null, "plan.rules.eligibility_required", "Elegibilidade obrigatória");
        Guard.require(attribution != null, "plan.rules.attribution_required", "Regras de atribuição obrigatórias");
        Guard.require(quality != null, "plan.rules.quality_required", "Modificadores de qualidade obrigatórios");
        Guard.require(limits != null, "plan.rules.limits_required", "Limites obrigatórios");
        Guard.require(clearing != null, "plan.rules.clearing_required", "Carência obrigatória");
        Guard.require(recoveryRate != null || fixedPerAgreement != null || teamPool != null,
                "plan.rules.no_component", "O plano precisa de ao menos um componente remunerável");
        eligibility.validate();
        if (recoveryRate != null) {
            recoveryRate.validate();
        }
        if (fixedPerAgreement != null) {
            fixedPerAgreement.validate();
        }
        if (goal != null) {
            goal.validate();
        }
        difficultyMultipliers.forEach(DifficultyMultiplier::validate);
        quality.validate();
        attribution.validate();
        if (teamPool != null) {
            teamPool.validate();
        }
        limits.validate();
        clearing.validate();
        return this;
    }

    /** Multiplicador de dificuldade do segmento: a seleção mais específica que casar; 1,0 se nenhuma. */
    public BigDecimal multiplierFor(Segment segment) {
        return difficultyMultipliers.stream().filter(m -> m.segment().matches(segment))
                .max(Comparator.comparingInt(m -> m.segment().specificity()))
                .map(DifficultyMultiplier::factor).orElse(BigDecimal.ONE);
    }

    static void percent(BigDecimal value, String field, boolean required) {
        if (value == null) {
            Guard.require(!required, "plan.rules." + field + "_required", "Campo obrigatório: " + field);
            return;
        }
        Guard.require(value.signum() >= 0 && value.compareTo(HUNDRED) <= 0, "plan.rules." + field + "_invalid",
                "Percentual fora do intervalo 0–100: " + field);
    }

    static void factor(BigDecimal value, String field) {
        Guard.require(value != null && value.signum() >= 0 && value.compareTo(MAX_FACTOR) <= 0,
                "plan.rules." + field + "_invalid", "Fator deve estar entre 0 e 10: " + field);
    }

    // ------------------------------------------------------------------ componentes

    /**
     * @param roleCodes      perfis elegíveis (vazio = qualquer perfil)
     * @param teamIds        equipes elegíveis (vazio = qualquer equipe ou nenhuma)
     * @param creditorIds    credores cobertos (vazio = todos): créditos de outros credores não remuneram
     * @param portfolioIds   carteiras cobertas (vazio = todas)
     * @param minWorkedDays  dias com tempo logado exigidos no período (0 = sem exigência)
     */
    public record Eligibility(Set<String> roleCodes, Set<UUID> teamIds, Set<UUID> creditorIds, Set<UUID> portfolioIds,
                              int minWorkedDays) {

        public Eligibility {
            roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
            teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
            creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
            portfolioIds = portfolioIds == null ? Set.of() : Set.copyOf(portfolioIds);
        }

        void validate() {
            Guard.require(minWorkedDays >= 0 && minWorkedDays <= 31, "plan.rules.min_worked_days_invalid",
                    "Dias trabalhados mínimos deve estar entre 0 e 31");
            roleCodes.forEach(code -> Guard.require(code != null && code.matches("[A-Z0-9_]{2,40}"),
                    "plan.rules.role_code_invalid", "Código de perfil inválido"));
        }

        public boolean coversMember(Set<String> memberRoles, Set<UUID> memberTeams) {
            return (roleCodes.isEmpty() || memberRoles.stream().anyMatch(roleCodes::contains))
                    && (teamIds.isEmpty() || memberTeams.stream().anyMatch(teamIds::contains));
        }

        public boolean coversSegment(Segment segment) {
            return (creditorIds.isEmpty() || creditorIds.contains(segment.creditorId()))
                    && (portfolioIds.isEmpty() || (segment.portfolioId() != null && portfolioIds.contains(segment.portfolioId())));
        }
    }

    public record RateTier(BigDecimal fromAmount, BigDecimal ratePercent) {
    }

    /** Tabela de faixas aplicável a um conjunto de segmentos. */
    public record RateTable(SegmentSelector segment, List<RateTier> tiers) {

        public RateTable {
            segment = segment == null ? SegmentSelector.ANY : segment;
            tiers = tiers == null ? List.of() : tiers.stream().sorted(Comparator.comparing(RateTier::fromAmount,
                    Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        }

        void validate() {
            Guard.require(!tiers.isEmpty(), "plan.rules.tiers_required", "Informe ao menos uma faixa");
            Guard.require(tiers.getFirst().fromAmount() != null && tiers.getFirst().fromAmount().signum() == 0,
                    "plan.rules.first_tier_invalid", "A primeira faixa deve começar em zero");
            for (int i = 0; i < tiers.size(); i++) {
                RateTier tier = tiers.get(i);
                Guard.require(tier.fromAmount() != null && tier.fromAmount().signum() >= 0, "plan.rules.tier_from_invalid",
                        "Início de faixa inválido");
                percent(tier.ratePercent(), "tier_rate", true);
                if (i > 0) {
                    Guard.require(tier.fromAmount().compareTo(tiers.get(i - 1).fromAmount()) > 0,
                            "plan.rules.tier_overlap", "Faixas devem ter inícios distintos e crescentes");
                }
            }
        }
    }

    public record RecoveryRateComponent(TierMode mode, List<RateTable> tables) {

        public RecoveryRateComponent {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }

        void validate() {
            Guard.require(mode != null, "plan.rules.tier_mode_required", "Modo das faixas obrigatório");
            Guard.require(!tables.isEmpty(), "plan.rules.rate_tables_required", "Informe ao menos uma tabela de taxas");
            tables.forEach(RateTable::validate);
        }

        /** Tabela mais específica que casa com o segmento (empate: a primeira declarada). */
        public Optional<RateTable> tableFor(Segment segment) {
            RateTable best = null;
            for (RateTable table : tables) {
                if (table.segment().matches(segment)
                        && (best == null || table.segment().specificity() > best.segment().specificity())) {
                    best = table;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    /** @param amount valor por acordo (como closer/influenciador) cuja 1ª parcela foi paga no período */
    public record FixedPerAgreementComponent(BigDecimal amount) {

        void validate() {
            Guard.require(amount != null && amount.signum() > 0 && amount.scale() <= 2,
                    "plan.rules.fixed_amount_invalid", "Valor fixo por acordo deve ser positivo (centavos)");
        }
    }

    /**
     * @param defaultTarget                meta padrão (R$ ou %, conforme a métrica); metas por
     *                                     período/pessoa prevalecem; null = só com meta cadastrada
     * @param triggerPercent               gatilho mínimo de atingimento (ex.: 80)
     * @param belowTriggerFactor           fator pago abaixo do gatilho (ex.: 0,5)
     * @param acceleratorThresholdPercent  atingimento a partir do qual acelera (ex.: 110)
     * @param acceleratorFactor            fator do acelerador (ex.: 1,2)
     * @param capPercent                   teto de atingimento remunerado (ex.: 300 = 3× a meta)
     */
    public record GoalComponent(GoalMetric metric, GoalScope scope, BigDecimal defaultTarget, BigDecimal triggerPercent,
                                BigDecimal belowTriggerFactor, BigDecimal acceleratorThresholdPercent,
                                BigDecimal acceleratorFactor, AcceleratorMode acceleratorMode, BigDecimal capPercent) {

        void validate() {
            Guard.require(metric != null && scope != null, "plan.rules.goal_metric_required", "Métrica e escopo da meta obrigatórios");
            Guard.require(metric != GoalMetric.RECOVERED_VS_EXPECTED, "plan.rules.goal_metric_unavailable",
                    "Meta por recuperado × esperado ainda não é apurada pelo motor de comissão "
                            + "(o indicador está disponível só para consulta)");
            Guard.require(defaultTarget == null || defaultTarget.signum() > 0, "plan.rules.goal_target_invalid",
                    "Meta padrão deve ser positiva");
            Guard.require(triggerPercent != null && triggerPercent.signum() >= 0 && triggerPercent.compareTo(new BigDecimal("1000")) <= 0,
                    "plan.rules.goal_trigger_invalid", "Gatilho inválido");
            factor(belowTriggerFactor, "goal_below_trigger_factor");
            Guard.require(belowTriggerFactor.compareTo(BigDecimal.ONE) <= 0, "plan.rules.goal_below_trigger_factor_invalid",
                    "Fator abaixo do gatilho não pode exceder 1");
            Guard.require(acceleratorThresholdPercent != null && acceleratorThresholdPercent.compareTo(triggerPercent) >= 0,
                    "plan.rules.goal_accelerator_threshold_invalid", "Limiar do acelerador deve ser ≥ gatilho");
            factor(acceleratorFactor, "goal_accelerator_factor");
            Guard.require(acceleratorFactor.compareTo(BigDecimal.ONE) >= 0, "plan.rules.goal_accelerator_factor_invalid",
                    "Fator do acelerador deve ser ≥ 1");
            Guard.require(acceleratorMode != null, "plan.rules.goal_accelerator_mode_required", "Modo do acelerador obrigatório");
            Guard.require(capPercent == null || capPercent.compareTo(acceleratorThresholdPercent) >= 0,
                    "plan.rules.goal_cap_invalid", "Teto deve ser ≥ limiar do acelerador");
        }
    }

    public record DifficultyMultiplier(SegmentSelector segment, BigDecimal factor) {

        public DifficultyMultiplier {
            segment = segment == null ? SegmentSelector.ANY : segment;
        }

        void validate() {
            PlanRules.factor(factor, "difficulty_factor");
        }
    }

    /**
     * @param windowDays       janela de quebra precoce (30, 60 ou 90 dias)
     * @param thresholdPercent percentual da safra acima do qual o deflator se aplica
     * @param deflatorPercent  redução do variável (ex.: 20)
     * @param minSample        acordos mínimos na safra para aplicar (evita penalizar amostra pequena)
     */
    public record EarlyBreakDeflator(int windowDays, BigDecimal thresholdPercent, BigDecimal deflatorPercent, int minSample) {

        void validate() {
            Guard.require(windowDays == 30 || windowDays == 60 || windowDays == 90, "plan.rules.early_break_window_invalid",
                    "Janela de quebra precoce deve ser 30, 60 ou 90 dias");
            percent(thresholdPercent, "early_break_threshold", true);
            percent(deflatorPercent, "early_break_deflator", true);
            Guard.require(minSample >= 1, "plan.rules.early_break_min_sample_invalid", "Amostra mínima deve ser ≥ 1");
        }
    }

    public record EnvelopeDeflator(BigDecimal thresholdPercent, BigDecimal deflatorPercent, int minSample) {

        void validate() {
            percent(thresholdPercent, "envelope_threshold", true);
            percent(deflatorPercent, "envelope_deflator", true);
            Guard.require(minSample >= 1, "plan.rules.envelope_min_sample_invalid", "Amostra mínima deve ser ≥ 1");
        }
    }

    /**
     * @param minScore                 nota média mínima de monitoria (0–10)
     * @param suspendWithoutEvaluation sem avaliação no período, suspende (true) ou libera (false)
     */
    public record MonitoringGate(BigDecimal minScore, boolean suspendWithoutEvaluation) {

        void validate() {
            Guard.require(minScore != null && minScore.signum() >= 0 && minScore.compareTo(BigDecimal.TEN) <= 0,
                    "plan.rules.monitoring_min_score_invalid", "Nota mínima deve estar entre 0 e 10");
        }
    }

    /** Modificadores de qualidade (§4.2); cada um é opcional. */
    public record QualityModifiers(EarlyBreakDeflator earlyBreak, EnvelopeDeflator envelope, MonitoringGate monitoringGate,
                                   boolean zeroOnSevereViolation) {

        void validate() {
            if (earlyBreak != null) {
                earlyBreak.validate();
            }
            if (envelope != null) {
                envelope.validate();
            }
            if (monitoringGate != null) {
                monitoringGate.validate();
            }
        }
    }

    /**
     * @param originWeights                  crédito individual (%) por origem (§3.2); todas as origens obrigatórias
     * @param influenceWindowDays            janela de influência para portal/campanha
     * @param ownRenegotiationWeightPercent  peso da renegociação de acordo do próprio negociador (§3.4)
     * @param terminationGraceDays           dias úteis após o desligamento (política CREDIT_UNTIL_TERMINATION_PLUS_GRACE)
     */
    public record AttributionRules(Map<AgreementOrigin, BigDecimal> originWeights, int influenceWindowDays,
                                   CreditModel creditModel, BigDecimal ownRenegotiationWeightPercent,
                                   TerminationPolicy terminationPolicy, int terminationGraceDays) {

        public AttributionRules {
            Map<AgreementOrigin, BigDecimal> copy = new EnumMap<>(AgreementOrigin.class);
            if (originWeights != null) {
                originWeights.forEach((origin, weight) -> {
                    if (origin != null && weight != null) {
                        copy.put(origin, weight);
                    }
                });
            }
            originWeights = Map.copyOf(copy);
        }

        void validate() {
            for (AgreementOrigin origin : AgreementOrigin.values()) {
                percent(originWeights.get(origin), "origin_weight_" + origin.name().toLowerCase(), true);
            }
            Guard.require(originWeights.get(AgreementOrigin.ASSISTANT_AUTONOMOUS).signum() == 0,
                    "plan.rules.autonomous_weight_invalid",
                    "Acordo autônomo do assistente não gera crédito individual (vai para o pool)");
            Guard.require(influenceWindowDays >= 0 && influenceWindowDays <= 90, "plan.rules.influence_window_invalid",
                    "Janela de influência deve estar entre 0 e 90 dias");
            Guard.require(creditModel != null, "plan.rules.credit_model_required", "Modelo de crédito obrigatório");
            percent(ownRenegotiationWeightPercent, "own_renegotiation_weight", true);
            Guard.require(terminationPolicy != null, "plan.rules.termination_policy_required", "Política de desligamento obrigatória");
            Guard.require(terminationGraceDays >= 0 && terminationGraceDays <= 60, "plan.rules.termination_grace_invalid",
                    "Carência de desligamento deve estar entre 0 e 60 dias úteis");
        }

        public BigDecimal weightOf(AgreementOrigin origin) {
            return originWeights.getOrDefault(origin, BigDecimal.ZERO);
        }
    }

    public record TeamPoolComponent(BigDecimal ratePercent, boolean includeAssistantAutonomous, PoolDistribution distribution) {

        void validate() {
            percent(ratePercent, "pool_rate", true);
            Guard.require(ratePercent.signum() > 0, "plan.rules.pool_rate_invalid", "Taxa do pool deve ser positiva");
            Guard.require(distribution != null, "plan.rules.pool_distribution_required", "Distribuição do pool obrigatória");
        }
    }

    /** @param periodCap teto do variável por período por negociador (null = sem teto); não há piso */
    public record Limits(BigDecimal periodCap) {

        void validate() {
            Guard.require(periodCap == null || periodCap.signum() > 0, "plan.rules.period_cap_invalid",
                    "Teto por período deve ser positivo");
        }
    }

    /**
     * @param graceBusinessDays dias úteis de carência após a confirmação do pagamento (§5.4)
     * @param clawbackEnabled   estorno de comissão de período já fechado (§5.2; exige validação jurídica)
     */
    public record ClearingRules(int graceBusinessDays, boolean clawbackEnabled) {

        void validate() {
            Guard.require(graceBusinessDays >= 0 && graceBusinessDays <= 60, "plan.rules.grace_days_invalid",
                    "Carência deve estar entre 0 e 60 dias úteis");
        }
    }
}
