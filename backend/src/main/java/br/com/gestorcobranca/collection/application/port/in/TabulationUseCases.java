package br.com.gestorcobranca.collection.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Tabulações (resultados de interação) configuráveis por assessoria. As flags definem os efeitos
 * do registro: {@code effectiveContact} (CPC, leva o caso a IN_NEGOTIATION), {@code promise}
 * (exige os dados de uma promessa), {@code callback} (exige o agendamento de um retorno) e
 * {@code requiresNote} (exige observação). A ação automática ({@code followUp}: NONE, OPEN_DISPUTE,
 * REPORT_DECEASED ou INVALIDATE_CONTACT) executa o efeito do fato relatado no próprio registro; toda
 * ação exige observação e não se combina com promessa ou retorno; OPEN_DISPUTE exige contato efetivo e
 * aceita um tipo de contestação pré-selecionado ({@code disputeType}). O código é imutável; tabulações são desativadas,
 * nunca removidas. A assessoria recebe um catálogo padrão no provisionamento.
 */
public interface TabulationUseCases {

    TabulationView create(TabulationCommand command);

    TabulationView update(UUID tabulationId, TabulationCommand command);

    TabulationView activate(UUID tabulationId);

    TabulationView deactivate(UUID tabulationId);

    /** @param activeOnly somente as ativas (tela de atendimento) */
    List<TabulationView> list(boolean activeOnly);

    /**
     * @param code        somente na criação
     * @param followUp    NONE (nulo), OPEN_DISPUTE, REPORT_DECEASED ou INVALIDATE_CONTACT
     * @param disputeType tipo de contestação pré-selecionado (só com OPEN_DISPUTE; nulo = o operador escolhe)
     */
    record TabulationCommand(String code, String name, boolean effectiveContact, boolean promise, boolean callback,
                             boolean requiresNote, String followUp, String disputeType, int displayOrder) {
    }

    record TabulationView(UUID id, String code, String name, boolean effectiveContact, boolean promise,
                          boolean callback, boolean requiresNote, String followUp, String disputeType,
                          int displayOrder, boolean active, long version) {
    }
}
