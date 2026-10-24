package br.com.gestorcobranca.telephony.domain.model;

/** Situação de um caso na lista da campanha de discagem. */
public enum DialerEntryStatus {
    /** Aguardando a próxima tentativa ({@code nextAttemptAt}). */
    PENDING,
    /** Chamada em andamento. */
    IN_PROGRESS,
    /** Atendida e conectada a um operador. */
    COMPLETED,
    /** Tentativas esgotadas sem atendimento. */
    EXHAUSTED,
    /** Fora da lista: caso encerrado/inelegível, sem telefone, restrição sem fim previsto ou campanha encerrada. */
    EXCLUDED
}
