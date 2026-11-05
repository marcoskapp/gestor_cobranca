package br.com.gestorcobranca.collection.domain.model;

/** Como a resposta do credor chegou: registrada pela assessoria, enviada pelo credor na API pública ou por e-mail (confirmada pelo analista). */
public enum CreditorResponseSource {
    MANUAL,
    PUBLIC_API,
    EMAIL
}
