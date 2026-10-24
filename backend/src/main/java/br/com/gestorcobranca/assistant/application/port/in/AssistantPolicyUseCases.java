package br.com.gestorcobranca.assistant.application.port.in;

import br.com.gestorcobranca.assistant.domain.model.PolicySettings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Políticas do assistente (versionadas por assessoria, credor ou carteira) com {@code ASSISTANT_MANAGE}.
 * <ul>
 *   <li>Rascunho editável; publicar torna a versão imutável, aposenta a publicada anterior do mesmo
 *       escopo e — se a política indicar um perfil de alçada — vincula o perfil à versão no negotiation
 *       ({@code AuthorityUseCases#assign}, sujeito {@code ASSISTANT_POLICY}; exige {@code AUTHORITY_MANAGE}).</li>
 *   <li>Escopo: política de credor/carteira exige o credor no escopo; da assessoria, todos os credores.</li>
 *   <li>{@link #disablePortfolio}: desligamento imediato de uma carteira (versão {@code OFF} da carteira,
 *       publicada na hora) e transferência para humanos das conversas que o assistente conduz nela.</li>
 * </ul>
 * Tudo auditado; publicação registra {@code policy.published} ({@code policyKind = assistant}).
 */
public interface AssistantPolicyUseCases {

    List<PolicyView> list();

    PolicyView get(UUID policyId);

    /** Histórico de versões do código. */
    List<PolicyView> versions(String code);

    PolicyView createDraft(CreatePolicyCommand command);

    PolicyView updateDraft(UUID policyId, UpdatePolicyCommand command);

    /** Novo rascunho (próxima versão) copiando os parâmetros da versão indicada. */
    PolicyView newVersion(UUID policyId);

    PolicyView publish(UUID policyId);

    PolicyView retire(UUID policyId, String reason);

    /** Política vigente para o credor/carteira (vazia = o assistente não atua). */
    java.util.Optional<PolicyView> effective(UUID creditorId, UUID portfolioId);

    /** @return versão OFF publicada para a carteira */
    PolicyView disablePortfolio(UUID portfolioId, String reason);

    /** Parâmetros padrão para um modo (modelo de cadastro). */
    PolicySettings template(String mode);

    /** Prompts versionados disponíveis. */
    List<PromptView> prompts();

    /**
     * @param code  código legível (2 a 40: minúsculas, dígitos, hífen), único por escopo
     */
    record CreatePolicyCommand(UUID creditorId, UUID portfolioId, String code, String name, PolicySettings settings) {
    }

    record UpdatePolicyCommand(String name, PolicySettings settings) {
    }

    /** @param authorityProfileCode perfil de alçada efetivo do assistente para o credor da política */
    record PolicyView(UUID id, String code, String name, UUID creditorId, UUID portfolioId, int policyVersion,
                      String status, String mode, PolicySettings settings, String promptCode, int promptVersion,
                      Instant publishedAt, UUID publishedBy, Instant retiredAt, String retireReason, Instant createdAt,
                      long version) {
    }

    record PromptView(String code, int version, String description, String sha256, int length) {
    }
}
