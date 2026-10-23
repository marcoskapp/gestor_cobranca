package br.com.gestorcobranca.telephony.domain.service;

import br.com.gestorcobranca.telephony.domain.model.CallResult;

/**
 * Tabulações automáticas (catálogo do collection) por resultado da chamada, usadas quando não há
 * operador (discador sem atendimento) ou quando o prazo do pós-atendimento vence. Nenhuma é
 * contato efetivo: só o operador tabula o contato com a pessoa certa.
 */
public final class CallTabulations {

    public static final String NO_ANSWER = "NO_ANSWER";
    public static final String BUSY = "BUSY";
    public static final String VOICEMAIL = "VOICEMAIL";
    public static final String CALL_ABANDONED = "CALL_ABANDONED";
    public static final String CALL_FAILED = "CALL_FAILED";
    public static final String CALL_ANSWERED = "CALL_ANSWERED";

    private CallTabulations() {
    }

    /** Sugestão ao operador e tabulação automática. */
    public static String automatic(CallResult result) {
        return switch (result) {
            case ANSWERED -> CALL_ANSWERED;
            case NO_ANSWER, CANCELLED -> NO_ANSWER;
            case BUSY -> BUSY;
            case VOICEMAIL -> VOICEMAIL;
            case ABANDONED -> CALL_ABANDONED;
            case FAILED -> CALL_FAILED;
        };
    }
}
