package br.com.gestorcobranca.incentive.domain.model.plan;

/** Ciclo de vida de uma versão de plano: {@code DRAFT → PUBLISHED → RETIRED}. */
public enum PlanStatus {
    DRAFT, PUBLISHED, RETIRED
}
