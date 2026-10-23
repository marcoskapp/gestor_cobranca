package br.com.gestorcobranca.communication.domain.model;

/** Ciclo de vida de uma versão de template. */
public enum TemplateStatus {
    /** Editável; ainda não pode ser usado em envios. */
    DRAFT,
    /** Versão vigente do código (uma por credor e código). */
    PUBLISHED,
    /** Substituída por versão mais nova ou retirada manualmente. */
    RETIRED
}
