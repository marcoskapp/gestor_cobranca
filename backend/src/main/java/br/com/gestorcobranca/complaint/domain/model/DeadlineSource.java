package br.com.gestorcobranca.complaint.domain.model;

/** De onde veio o prazo de resposta. */
public enum DeadlineSource {
    /** Informado na notificação do canal (ex.: prazo dado pelo Procon). */
    INFORMED,
    /** Regra do canal configurada pela assessoria. */
    CHANNEL_RULE,
    /** Padrão do sistema (sem regra para o canal). */
    DEFAULT
}
