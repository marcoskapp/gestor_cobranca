package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Ramal do operador numa conta (plataforma) específica. Sem registro para a conta vale o ramal
 * padrão do operador ({@link Agent#getExtension()}), útil quando o operador é alcançável por todas as
 * plataformas (ex.: URI SIP do PABX da assessoria). Registro inativo = o operador <b>não</b> atende
 * por aquela conta: ela não lhe entrega chamadas nem o usa no clique para ligar.
 */
@Entity
@Table(name = "agent_endpoint", schema = "telephony")
public class AgentEndpoint extends TenantScopedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "provider_account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "extension", length = 64)
    private String extension;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected AgentEndpoint() {
    }

    public AgentEndpoint(UUID agentId, UUID accountId, String extension, boolean active) {
        Guard.require(agentId != null && accountId != null, "agent_endpoint.required", "Operador e conta obrigatórios");
        this.agentId = agentId;
        this.accountId = accountId;
        change(extension, active);
    }

    public void change(String extension, boolean active) {
        String value = extension == null || extension.isBlank() ? null : extension.trim();
        Guard.require(!active || value != null, "agent_endpoint.extension_required",
                "Informe o ramal do operador nesta conta");
        Guard.require(value == null || Agent.validExtension(value), "agent.extension_invalid",
                "Ramal inválido (2 a 64 caracteres: letras, dígitos e . _ - @ : +)");
        this.extension = active ? value : null;
        this.active = active;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getExtension() {
        return extension;
    }

    public boolean isActive() {
        return active;
    }
}
