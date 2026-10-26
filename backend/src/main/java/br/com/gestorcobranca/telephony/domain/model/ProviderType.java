package br.com.gestorcobranca.telephony.domain.model;

import java.util.Set;

/**
 * Provedores de telefonia/discador. Uma assessoria pode ter várias contas, de provedores
 * diferentes, ativas ao mesmo tempo (roteamento por escopo, {@code AccountRoute}). Se o adaptador
 * de um provedor está pronto para produção é dito pelo próprio adaptador
 * ({@code TelephonyProvider#readiness}); ver {@code docs/tecnico/desenho/telefonia.md} §9 e os
 * anexos por provedor.
 */
public enum ProviderType {
    /** Simulador local/teste: credencial {@code webhookSecret} assina os eventos simulados. */
    SANDBOX("Simulador", Set.of("webhookSecret")),
    /**
     * Alvaria CX (outbound da antiga Aspect Software, fundida à Noble Systems em 2021; a marca Aspect
     * voltou em 2024 só para WFM/WEM). API não pública: endereço da instância da assessoria, cliente
     * OAuth e segredo dos eventos — nomes a confirmar com a Alvaria (anexo A do desenho).
     */
    ALVARIA("Alvaria CX (Aspect)", Set.of("apiBaseUrl", "clientId", "clientSecret", "webhookSecret")),
    /**
     * Tarifando Telecom (PABX IP/Call Center em nuvem). API REST citada pelo fornecedor, sem
     * documentação pública: credenciais provisórias, adaptador desligado (anexo B do desenho).
     */
    TARIFANDO("Tarifando Telecom", Set.of("apiBaseUrl", "apiToken", "webhookSecret"));

    private final String label;
    private final Set<String> requiredCredentials;

    ProviderType(String label, Set<String> requiredCredentials) {
        this.label = label;
        this.requiredCredentials = requiredCredentials;
    }

    public String label() {
        return label;
    }

    public Set<String> requiredCredentials() {
        return requiredCredentials;
    }
}
