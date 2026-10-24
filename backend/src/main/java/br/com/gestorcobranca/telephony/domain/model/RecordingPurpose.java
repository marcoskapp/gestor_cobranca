package br.com.gestorcobranca.telephony.domain.model;

/** Finalidade declarada para ouvir/baixar uma gravação (registrada no log de acesso). */
public enum RecordingPurpose {
    QUALITY_MONITORING,
    DISPUTE,
    DEBTOR_REQUEST,
    LEGAL,
    AUDIT
}
