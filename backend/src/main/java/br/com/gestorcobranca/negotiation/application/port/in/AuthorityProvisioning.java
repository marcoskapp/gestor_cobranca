package br.com.gestorcobranca.negotiation.application.port.in;

/** Configuração inicial de alçadas numa assessoria. */
public interface AuthorityProvisioning {

    /**
     * Semeia os perfis padrão (JUNIOR, PLENO, SENIOR, SUPERVISOR, GESTOR, ASSISTENTE_INICIAL) que
     * ainda não existirem. Idempotente: perfis existentes, inclusive ajustados, não são alterados.
     *
     * @return quantidade de perfis criados agora
     */
    int ensureDefaultProfiles();
}
