package br.com.gestorcobranca.compliance.domain.model;

/** Motivo pelo qual um contato não é permitido no instante avaliado. */
public enum DenialReason {
    /** Restrição que alcança o devedor em todos os canais. */
    RESTRICTED_DEBTOR,
    /** Restrição do devedor no canal solicitado. */
    RESTRICTED_CHANNEL,
    /** Restrição do valor de contato (telefone ou e-mail) informado. */
    RESTRICTED_CONTACT_VALUE,
    /** Fora da janela de horário ou dia da semana permitido. */
    OUTSIDE_WINDOW,
    /** Feriado cadastrado, com bloqueio ativo na política do canal. */
    HOLIDAY,
    /** Limite diário de tentativas do canal atingido. */
    DAILY_LIMIT,
    /** Limite semanal de tentativas do canal atingido. */
    WEEKLY_LIMIT,
    /** Intervalo mínimo desde a última tentativa no canal não decorrido. */
    MIN_INTERVAL,
    /** Dados pessoais do devedor eliminados (LGPD): não há mais com quem falar. */
    DEBTOR_ANONYMIZED
}
