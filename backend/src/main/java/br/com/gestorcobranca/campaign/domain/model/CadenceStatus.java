package br.com.gestorcobranca.campaign.domain.model;

/** Ciclo de uma versão de régua: rascunho editável, publicada (imutável, usada nas novas inscrições) e retirada. */
public enum CadenceStatus {
    DRAFT, PUBLISHED, RETIRED
}
