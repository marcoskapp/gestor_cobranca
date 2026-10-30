package br.com.gestorcobranca.publicapi.domain.model;

/** Ciclo de vida de uma versão da API pública (docs/tecnico/desenho/api-publica.md §8). */
public enum ApiVersionStatus {
    /** Versão em uso, recomendada. */
    CURRENT,
    /** Ainda atendida, com cabeçalhos {@code Deprecation} e {@code Sunset}; os clientes devem migrar. */
    DEPRECATED,
    /** Desligada: respostas 410 Gone. */
    RETIRED
}
