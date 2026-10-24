package br.com.gestorcobranca.telephony.domain.model;

/**
 * Resultado consolidado da chamada encerrada, publicado em {@code CallCompleted.result} (contrato
 * com o incentive: somente {@code ANSWERED} entra no TMA e na ocupação).
 */
public enum CallResult {
    /** Atendida por pessoa e conectada a um operador. */
    ANSWERED,
    NO_ANSWER,
    BUSY,
    /** Atendida por secretária eletrônica/caixa postal (AMD) e desligada sem operador. */
    VOICEMAIL,
    FAILED,
    CANCELLED,
    /**
     * Atendida por pessoa sem operador livre para receber (discador): conta na taxa de abandono e
     * é encerrada pelo sistema com mensagem informativa.
     */
    ABANDONED
}
