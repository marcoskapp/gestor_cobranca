package br.com.gestorcobranca.telephony.domain.model;

/** Modo de ritmo da campanha de discagem. */
public enum DialerMode {
    /** Uma chamada por operador livre (sem abandono por falta de operador, salvo corrida). */
    PROGRESSIVE,
    /** Mais de uma chamada por operador livre, pela taxa de atendimento observada, limitada pelo abandono máximo. */
    PREDICTIVE
}
