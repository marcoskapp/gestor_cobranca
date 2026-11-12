package br.com.gestorcobranca.debt.application.port.in;

import java.time.Instant;

/**
 * Prazo da janela de novidade dos contatos recebidos numa carga para devedor já existente
 * (docs/tecnico/desenho/contatos-recebidos.md §2), por assessoria. Exige {@code DEBTOR_MANAGE} (a mesma do cadastro de
 * contatos). A alteração vale para os contatos recebidos depois dela e é auditada
 * ({@code contact_novelty_settings.updated}).
 */
public interface ContactNoveltyUseCases {

    ContactNoveltySettingsView settings();

    /** Prazo de 1 a 180 dias ({@code contact_novelty.window_invalid} fora da faixa). */
    ContactNoveltySettingsView updateSettings(int windowDays);

    /**
     * @param windowDays        prazo em vigor (o da assessoria ou, sem configuração, o padrão do sistema)
     * @param defaultWindowDays padrão do sistema ({@code app.debt.contact-novelty.window-days})
     * @param customized        a assessoria gravou um prazo próprio
     * @param updatedAt         última alteração do prazo próprio (nulo sem configuração)
     */
    record ContactNoveltySettingsView(int windowDays, int defaultWindowDays, int minWindowDays, int maxWindowDays,
                                      boolean customized, Instant updatedAt) {
    }
}
