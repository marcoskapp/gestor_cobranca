package br.com.gestorcobranca.scoring.domain.model;

/**
 * Tipos de fato materializados a partir de eventos de integração (tabela {@code scoring.case_fact},
 * append-only). Só ids, datas, valores, canal e situação — nada pessoal.
 */
public enum FactKind {
    /** Interação de saída registrada no caso (tentativa). */
    CONTACT_ATTEMPT,
    /** Interação com contato efetivo (pessoa certa). */
    EFFECTIVE_CONTACT,
    /** Devedor respondeu: interação de entrada, mensagem recebida ou ligação atendida. */
    DEBTOR_RESPONSE,
    /** Mudança de situação de promessa simples ({@code status}: PENDING, KEPT, BROKEN, CANCELLED). */
    PROMISE,
    /** Mudança de situação de acordo ({@code status}: FORMALIZED, IN_PAYMENT, SETTLED, BROKEN…). */
    AGREEMENT,
    /** Pagamento confirmado (alocação; {@code referenceId} = id do evento de pagamento). */
    PAYMENT,
    /** Estorno de pagamento ({@code referenceId} = pagamento estornado). */
    PAYMENT_REVERSAL
}
