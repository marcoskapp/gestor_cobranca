package br.com.gestorcobranca.telephony.domain.model;

/** Detecção de secretária eletrônica (AMD) informada pelo provedor no atendimento. */
public enum AmdResult {
    HUMAN,
    MACHINE,
    /** Provedor não conseguiu classificar (tratado como pessoa: não se desliga na cara de quem atendeu). */
    UNKNOWN
}
