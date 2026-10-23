package br.com.gestorcobranca.incentive.domain.model.quality;

/** Ocorrência de conduta atribuída a um negociador (§2.4 e zerador do §4.2). */
public enum OccurrenceType {
    /** Violação grave de compliance comprovada: zera o variável do período quando o plano prevê. */
    SEVERE_COMPLIANCE_VIOLATION,
    COMPLIANCE_VIOLATION,
    /** Reclamação registrada (ouvidoria, Procon, consumidor.gov). */
    COMPLAINT,
    /** Contestação do devedor julgada procedente em caso do negociador. */
    UPHELD_DISPUTE
}
