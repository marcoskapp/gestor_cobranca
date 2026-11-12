package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Parâmetros da operação de cobrança por assessoria. Enquanto a assessoria não grava os seus,
 * valem os padrões de {@code app.collection.defaults}. Os prazos são fixados nos registros
 * (promessa, retorno, contestação) no momento em que são criados: alterar um parâmetro não muda
 * compromissos já assumidos.
 */
@Entity
@Table(name = "settings", schema = "collection")
public class CollectionSettings extends TenantScopedEntity {

    @Column(name = "promise_tolerance_days", nullable = false)
    private int promiseToleranceDays;

    @Column(name = "promise_max_days", nullable = false)
    private int promiseMaxDays;

    @Column(name = "callback_tolerance_minutes", nullable = false)
    private int callbackToleranceMinutes;

    @Column(name = "dispute_response_days", nullable = false)
    private int disputeResponseDays;

    /** Multiplicador de capacidade por nível de desempenho, em % ({@code {"A":120,"B":110,"C":100,"D":80}}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tier_capacity", nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> tierCapacity;

    @Column(name = "tier_development_share", nullable = false)
    private int tierDevelopmentShare;

    @Column(name = "tier_default", nullable = false, length = 1, columnDefinition = "varchar(1)")
    private String tierDefault;

    protected CollectionSettings() {
    }

    public CollectionSettings(Values values) {
        define(values);
        defineTierPolicy(TierPolicy.DEFAULT);
    }

    /**
     * @param promiseToleranceDays     dias após a data prometida em que um pagamento ainda cumpre a promessa (0–30)
     * @param promiseMaxDays           prazo máximo, em dias a partir de hoje, para a data prometida (1–180)
     * @param callbackToleranceMinutes minutos após o horário combinado em que o retorno ainda conta como cumprido (0–1440)
     * @param disputeResponseDays      prazo padrão de resposta a uma contestação, em dias corridos (1–90)
     */
    public record Values(int promiseToleranceDays, int promiseMaxDays, int callbackToleranceMinutes,
                         int disputeResponseDays) {

        public Values {
            Guard.require(promiseToleranceDays >= 0 && promiseToleranceDays <= 30, "settings.promise_tolerance_invalid",
                    "Tolerância da promessa entre 0 e 30 dias");
            Guard.require(promiseMaxDays >= 1 && promiseMaxDays <= 180, "settings.promise_max_days_invalid",
                    "Prazo máximo da promessa entre 1 e 180 dias");
            Guard.require(callbackToleranceMinutes >= 0 && callbackToleranceMinutes <= 1440,
                    "settings.callback_tolerance_invalid", "Tolerância do retorno entre 0 e 1440 minutos");
            Guard.require(disputeResponseDays >= 1 && disputeResponseDays <= 90, "settings.dispute_response_days_invalid",
                    "Prazo de resposta da contestação entre 1 e 90 dias");
        }

        public Duration callbackTolerance() {
            return Duration.ofMinutes(callbackToleranceMinutes);
        }
    }

    public void define(Values values) {
        Guard.require(values != null, "settings.required", "Parâmetros obrigatórios");
        this.promiseToleranceDays = values.promiseToleranceDays();
        this.promiseMaxDays = values.promiseMaxDays();
        this.callbackToleranceMinutes = values.callbackToleranceMinutes();
        this.disputeResponseDays = values.disputeResponseDays();
    }

    /** Parâmetros da distribuição por nível de desempenho (distribuicao-por-desempenho.md §11.3). */
    public void defineTierPolicy(TierPolicy policy) {
        Guard.require(policy != null, "settings.required", "Parâmetros obrigatórios");
        Map<String, Integer> capacity = new HashMap<>();
        policy.capacityPercent().forEach((tier, percent) -> capacity.put(tier.name(), percent));
        this.tierCapacity = capacity;
        this.tierDevelopmentShare = policy.developmentShare();
        this.tierDefault = policy.defaultTier().name();
    }

    public TierPolicy tierPolicy() {
        Map<PerformanceTier, Integer> capacity = new EnumMap<>(PerformanceTier.class);
        tierCapacity.forEach((tier, percent) -> capacity.put(PerformanceTier.valueOf(tier), percent));
        return new TierPolicy(capacity, tierDevelopmentShare, PerformanceTier.valueOf(tierDefault));
    }

    public Values values() {
        return new Values(promiseToleranceDays, promiseMaxDays, callbackToleranceMinutes, disputeResponseDays);
    }
}
