package br.com.gestorcobranca.campaign.application.port.in;

import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Testes A/B (F25): variantes de template, canal, horário e oferta sobre a régua publicada, com grupo
 * de controle e atribuição aleatória estável por devedor ({@code experiment.variant_assigned}).
 * Resultados por braço com denominador explícito (devedores do braço), intervalo de confiança de 95%
 * e aviso de amostra insuficiente — método em {@code docs/tecnico/desenho/campanhas.md} §6.
 * <p>
 * Escopo: o credor da campanha. Um experimento aberto (rascunho ou em execução) por campanha.
 */
public interface ExperimentUseCases {

    ExperimentView create(UUID campaignId, ExperimentInput input);

    ExperimentView update(UUID experimentId, ExperimentInput input);

    /** Inicia: variantes validadas contra a régua publicada e congeladas. */
    ExperimentView start(UUID experimentId);

    ExperimentView stop(UUID experimentId, String reason);

    ExperimentView get(UUID experimentId);

    List<ExperimentView> list(UUID campaignId);

    /**
     * @param matureOnly somente devedores inscritos há pelo menos o horizonte de observação (padrão):
     *                   todos os braços observados pelo mesmo período
     */
    ExperimentResults results(UUID experimentId, boolean matureOnly);

    /**
     * @param primaryMetric EFFECTIVE_CONTACT, REPLY, AGREEMENT, PAYMENT ou RECOVERED_PER_DEBTOR (padrão PAYMENT)
     * @param controlShare  % de devedores no grupo de controle (0 a 50; padrão 10)
     * @param observationDays horizonte de observação dos resultados (padrão 30)
     * @param minSamplePerArm amostra mínima por braço para não haver aviso (padrão 100)
     */
    record ExperimentInput(String name, String hypothesis, String primaryMetric, BigDecimal controlShare,
                           List<ExperimentVariant> variants, Integer observationDays, Integer minSamplePerArm) {
    }

    record ExperimentView(UUID id, UUID campaignId, String name, String hypothesis, String primaryMetric, String status,
                          BigDecimal controlShare, List<ExperimentVariant> variants, int observationDays,
                          int minSamplePerArm, Instant startedAt, Instant stoppedAt, String stopReason,
                          Instant createdAt, long version) {
    }

    /**
     * @param baselineArm braço de referência das diferenças (CONTROL ou, sem controle, a primeira variante)
     * @param warnings    avisos gerais (ex.: amostra insuficiente na métrica principal)
     */
    record ExperimentResults(UUID experimentId, String status, String primaryMetric, int observationDays,
                             boolean matureOnly, Instant computedAt, String baselineArm, List<ArmResult> arms,
                             List<String> warnings) {
    }

    /** @param debtors denominador de todas as métricas do braço */
    record ArmResult(String arm, boolean control, long debtors, List<MetricResult> metrics) {
    }

    /**
     * @param successes  devedores com o resultado (nulo na métrica de valor)
     * @param estimate   taxa (0–1) ou valor médio por devedor
     * @param difference diferença para o braço de referência (nula no próprio)
     */
    record MetricResult(String metric, Long successes, long denominator, BigDecimal estimate, BigDecimal lower,
                        BigDecimal upper, Difference difference, List<String> warnings) {
    }

    /** @param significant o intervalo de 95% da diferença não contém zero */
    record Difference(BigDecimal estimate, BigDecimal lower, BigDecimal upper, boolean significant) {
    }
}
