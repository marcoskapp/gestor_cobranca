package br.com.gestorcobranca.collection.domain.model;

/**
 * Situação da tarefa: {@code PENDING → DONE | CANCELLED | MISSED}. {@code MISSED} é marcada pelo
 * job de vencimento quando a tarefa passa da tolerância sem ser concluída; ainda pode ser
 * concluída depois (registro tardio), mas segue contando como perdida nos indicadores.
 */
public enum TaskStatus {
    PENDING,
    DONE,
    CANCELLED,
    MISSED;

    public boolean isOpen() {
        return this == PENDING || this == MISSED;
    }
}
