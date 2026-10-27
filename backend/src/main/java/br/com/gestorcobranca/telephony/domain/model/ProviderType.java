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
    SANDBOX("Simulador", Set.of("webhookSecret"), Set.of()),
    /**
     * Alvaria CX (outbound da antiga Aspect Software, fundida à Noble Systems em 2021; a marca Aspect
     * voltou em 2024 só para WFM/WEM). API não pública: endereço da instância da assessoria, cliente
     * OAuth e segredo dos eventos — nomes a confirmar com a Alvaria (anexo A do desenho).
     */
    ALVARIA("Alvaria CX (Aspect)", Set.of("apiBaseUrl", "clientId", "clientSecret", "webhookSecret"), Set.of()),
    /**
     * Tarifando Telecom (PABX IP/Call Center em nuvem). API REST citada pelo fornecedor, sem
     * documentação pública: credenciais provisórias, adaptador desligado (anexo B do desenho).
     */
    TARIFANDO("Tarifando Telecom", Set.of("apiBaseUrl", "apiToken", "webhookSecret"), Set.of()),
    /**
     * Twilio Programmable Voice (anexo C do desenho). REST com API Key (SID e segredo) da conta;
     * o Auth Token da conta valida a assinatura dos webhooks ({@code X-Twilio-Signature} só usa o
     * Auth Token). {@code twimlAppSid} (opcional) libera chamadas de saída no softphone do navegador.
     */
    TWILIO("Twilio", Set.of("accountSid", "apiKeySid", "apiKeySecret", "authToken"), Set.of("twimlAppSid")),
    /**
     * Plivo Voice (anexo D do desenho). REST com Auth ID e Auth Token (HTTP Basic); o mesmo Auth
     * Token assina os webhooks ({@code X-Plivo-Signature-V3}).
     */
    PLIVO("Plivo", Set.of("authId", "authToken"), Set.of());

    private final String label;
    private final Set<String> requiredCredentials;
    private final Set<String> optionalCredentials;

    ProviderType(String label, Set<String> requiredCredentials, Set<String> optionalCredentials) {
        this.label = label;
        this.requiredCredentials = requiredCredentials;
        this.optionalCredentials = optionalCredentials;
    }

    public String label() {
        return label;
    }

    public Set<String> requiredCredentials() {
        return requiredCredentials;
    }

    /** Credenciais aceitas além das obrigatórias (podem faltar). */
    public Set<String> optionalCredentials() {
        return optionalCredentials;
    }

    public boolean acceptsCredential(String key) {
        return requiredCredentials.contains(key) || optionalCredentials.contains(key);
    }
}
