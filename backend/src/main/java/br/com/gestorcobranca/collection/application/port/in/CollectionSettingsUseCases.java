package br.com.gestorcobranca.collection.application.port.in;

import java.time.Instant;

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

    record SettingsCommand(int promiseToleranceDays, int promiseMaxDays, int callbackToleranceMinutes,
                           int disputeResponseDays) {
    }

    /** @param customized falso enquanto valem os padrões da plataforma */
    record SettingsView(int promiseToleranceDays, int promiseMaxDays, int callbackToleranceMinutes,
                        int disputeResponseDays, boolean customized, Instant updatedAt) {
    }
}
