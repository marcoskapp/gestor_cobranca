package br.com.gestorcobranca.collection.application.port.in;

import java.time.Instant;
import java.util.Map;

/**
 * Parâmetros da operação de cobrança da assessoria (tolerâncias e prazos). Alterar exige acesso a
 * todos os credores; os novos valores valem para registros criados depois da alteração.
 */
public interface CollectionSettingsUseCases {

    SettingsView get();

    SettingsView update(SettingsCommand command);

    /**
     * Garante a configuração inicial do módulo na assessoria corrente (catálogo padrão de
     * tabulações). Idempotente; chamado no provisionamento ({@code TenantProvisioned}).
     *
     * @return quantidade de tabulações criadas agora
     */
    int ensureDefaults();

    /** Parâmetros da distribuição por nível de desempenho (distribuicao-por-desempenho.md §11.3). */
    TierSettingsView tierSettings();

    /** Altera os parâmetros da distribuição por nível. Exige acesso a todos os credores; vale na próxima distribuição. */
    TierSettingsView updateTierSettings(TierSettingsCommand command);

    /**
     * @param capacityPercent  multiplicador de capacidade por nível (A, B, C, D), em % da capacidade da fila (50–200)
     * @param developmentShare % dos casos de alto potencial entregues por rodízio a todos os níveis (0–50)
     * @param defaultTier      nível de quem ainda não tem nível publicado (A, B, C ou D)
     */
    record TierSettingsCommand(Map<String, Integer> capacityPercent, int developmentShare, String defaultTier) {
    }

    /** @param customized falso enquanto vale o padrão aprovado (A 120, B 110, C 100, D 80; 15%; B) */
    record TierSettingsView(Map<String, Integer> capacityPercent, int developmentShare, String defaultTier,
                            boolean customized, Instant updatedAt) {
    }

    record SettingsCommand(int promiseToleranceDays, int promiseMaxDays, int callbackToleranceMinutes,
                           int disputeResponseDays) {
    }

    /** @param customized falso enquanto valem os padrões da plataforma */
    record SettingsView(int promiseToleranceDays, int promiseMaxDays, int callbackToleranceMinutes,
                        int disputeResponseDays, boolean customized, Instant updatedAt) {
    }
}
