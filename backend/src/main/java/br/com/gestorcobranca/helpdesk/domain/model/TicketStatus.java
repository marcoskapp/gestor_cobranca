package br.com.gestorcobranca.helpdesk.domain.model;

/**
 * Ciclo do chamado: {@code DRAFT} (preparado pela IA, aguardando a confirmação) → {@code CREATING} (abrindo no
 * GitHub) → {@code OPENED}; {@code DUPLICATE} quando o mesmo texto já foi aberto pela mesma pessoa; {@code FAILED}
 * permite tentar de novo; {@code CANCELLED} pela pessoa; {@code EXPIRED} pelo prazo do rascunho.
 */
public enum TicketStatus {
    DRAFT, CREATING, OPENED, DUPLICATE, FAILED, CANCELLED, EXPIRED;

    public boolean terminal() {
        return this == OPENED || this == DUPLICATE || this == CANCELLED || this == EXPIRED;
    }
}
