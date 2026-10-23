package br.com.gestorcobranca.telephony.domain.model;

public enum RecordingStatus {
    /** Disponível no provedor, aguardando cópia para o armazenamento próprio. */
    PENDING,
    STORED,
    /** Cópia falhou após as tentativas. */
    FAILED,
    /** Arquivo descartado (retenção ou anonimização); metadados permanecem. */
    PURGED
}
