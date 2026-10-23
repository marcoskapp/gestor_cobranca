package br.com.gestorcobranca.negotiation.domain.model.policy;

/** Ciclo de vida de uma versão de política: {@code DRAFT → PUBLISHED → RETIRED}. */
public enum PolicyStatus {
    /** Rascunho editável. */
    DRAFT,
    /** Publicada: imutável, vigente a partir de {@code effectiveFrom} (até {@code effectiveTo}). */
    PUBLISHED,
    /** Aposentada: vigência encerrada; permanece para reprodução de propostas antigas (RB03). */
    RETIRED
}
