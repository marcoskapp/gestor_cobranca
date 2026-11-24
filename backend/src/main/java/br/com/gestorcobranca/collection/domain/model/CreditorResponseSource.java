package br.com.gestorcobranca.collection.domain.model;

/**
 * Como a resposta do credor chegou: registrada pela assessoria, enviada pelo credor na API pública, por e-mail
 * (confirmada pelo analista) ou pela própria pessoa do credor no portal do credor.
 */
public enum CreditorResponseSource {
    MANUAL,
    PUBLIC_API,
    EMAIL,
    CREDITOR_PORTAL;

    /**
     * Origem externa: os documentos vêm de fora da assessoria e passam pela quarentena (tipo pelo conteúdo, limite e
     * verificação de malware) antes de virar evidência.
     */
    public boolean isExternal() {
        return this != MANUAL;
    }
}
