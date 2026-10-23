package br.com.gestorcobranca.negotiation.application.port.in;

import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Políticas de cobrança e negociação versionadas por credor ({@code DRAFT → PUBLISHED → RETIRED}).
 * Consultas exigem {@code POLICY_VIEW}; alterações {@code POLICY_MANAGE}; sempre no escopo de
 * credores do ator.
 */
public interface CollectionPolicyUseCases {

    /** Cria a primeira versão (rascunho) da política do credor; um credor tem uma única linha de versões. */
    PolicyView createDraft(CreatePolicyCommand command);

    PolicyView updateDraft(UUID policyId, UpdatePolicyCommand command);

    /** Nova versão em rascunho copiando os termos da versão informada; um rascunho por credor. */
    PolicyView newVersion(UUID policyId);

    /** Publica a partir de hoje ou de data futura; encerra a vigência da versão anterior. */
    PolicyView publish(UUID policyId, LocalDate effectiveFrom);

    /** Encerra a vigência em {@code effectiveTo} (inclusive; ontem = retirada imediata). */
    PolicyView retire(UUID policyId, LocalDate effectiveTo);

    PolicyView get(UUID policyId);

    /** @param status DRAFT, PUBLISHED ou RETIRED (opcional) */
    PageResponse<PolicyView> search(UUID creditorId, String status, PageQuery page);

    /**
     * Versão vigente do credor na data e regras efetivas para a carteira/faixa de atraso.
     *
     * @param date        data de vigência (nula = hoje)
     * @param portfolioId carteira (opcional)
     * @param daysOverdue atraso (opcional, nulo = 0)
     */
    EffectivePolicyView effective(UUID creditorId, UUID portfolioId, Long daysOverdue, LocalDate date);

    /** Modelo de termos com parâmetros usuais de mercado (ponto de partida do cadastro). */
    PolicyTerms template();

    record CreatePolicyCommand(UUID creditorId, String name, String description, PolicyTerms terms) {
    }

    record UpdatePolicyCommand(String name, String description, PolicyTerms terms) {
    }

    record PolicyView(UUID id, UUID creditorId, int version, String name, String description, String status,
                      LocalDate effectiveFrom, LocalDate effectiveTo, PolicyTerms terms, int termsSchemaVersion,
                      Instant publishedAt, UUID publishedBy, Instant retiredAt, Instant createdAt) {
    }

    /** @param overrideCode sobreposição aplicada (nula = regras base) */
    record EffectivePolicyView(UUID policyId, UUID creditorId, int version, LocalDate effectiveFrom,
                               LocalDate effectiveTo, String overrideCode, PolicyRules rules) {
    }
}
