package br.com.gestorcobranca.collection.domain.model;

/** Motivo pelo qual o caso não é elegível para contato (RB02). */
public enum EligibilityReason {
    CASE_CLOSED,
    CASE_SUSPENDED,
    CASE_LEGAL,
    /** Contestação aberta que bloqueia a cobrança. */
    DISPUTE_OPEN,
    /** Carteira inativa ou fora da vigência. */
    PORTFOLIO_NOT_OPERABLE,
    /** Nenhum título em aberto (todos suspensos ou sem saldo). */
    NO_OPEN_TITLES,
    /** Todos os títulos em aberto estão bloqueados por contestação (registrada no título). */
    TITLES_DISPUTED,
    /** Todos os canais estão restritos para o devedor/credor (opt-out, bloqueio). */
    CONTACT_RESTRICTED,
    /** Dados pessoais do devedor eliminados (LGPD). */
    DEBTOR_ANONYMIZED
}
