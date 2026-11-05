package br.com.gestorcobranca.collection.domain.model;

/** Distribuição das contestações novas: manual (fila sem responsável) ou automática para o analista com menos contestações abertas. */
public enum DisputeDistributionMode {
    MANUAL,
    LEAST_LOADED
}
