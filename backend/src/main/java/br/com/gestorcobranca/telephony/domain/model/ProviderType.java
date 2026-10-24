package br.com.gestorcobranca.telephony.domain.model;

import java.util.Set;

/**
 * Provedores de telefonia/discador. O provedor real aguarda aprovação (issue #12); até lá só existe
 * o simulador ({@code SANDBOX}, recusado no perfil {@code prod}). Para plugar um provedor real, ver
 * {@code docs/tecnico/desenho/telefonia.md} §9.
 */
public enum ProviderType {
    /** Simulador local/teste: credencial {@code webhookSecret} assina os eventos simulados. */
    SANDBOX(Set.of("webhookSecret"));

    private final Set<String> requiredCredentials;

    ProviderType(Set<String> requiredCredentials) {
        this.requiredCredentials = requiredCredentials;
    }

    public Set<String> requiredCredentials() {
        return requiredCredentials;
    }
}
