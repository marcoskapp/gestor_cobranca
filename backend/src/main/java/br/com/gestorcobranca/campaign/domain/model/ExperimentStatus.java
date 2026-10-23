package br.com.gestorcobranca.campaign.domain.model;

/** Ciclo do teste A/B: rascunho editável, em execução (variantes congeladas) e encerrado. */
public enum ExperimentStatus {
    DRAFT, RUNNING, STOPPED
}
