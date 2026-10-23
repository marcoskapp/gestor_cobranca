package br.com.gestorcobranca.negotiation.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Perfis de alçada e sua atribuição a usuários e a políticas do assistente (§4). Manutenção com
 * {@code AUTHORITY_MANAGE}; a consulta da própria alçada ({@link #mine}) com
 * {@code NEGOTIATION_SIMULATE}.
 * <p>
 * Regras de segurança: ninguém atribui alçada a si mesmo; atribuição geral (todos os credores)
 * exige escopo de todos os credores; atribuição por credor exige o credor no escopo; o perfil
 * precisa valer para o credor. Mudanças são auditadas e registradas no log de acesso.
 */
public interface AuthorityUseCases {

    List<ProfileView> listProfiles();

    ProfileView getProfile(UUID profileId);

    ProfileView createProfile(ProfileCommand command);

    /** Ajusta parâmetros (o código é imutável). */
    ProfileView updateProfile(UUID profileId, ProfileCommand command);

    /**
     * Atribui (ou troca) o perfil do sujeito para o credor ({@code creditorId} nulo = todos).
     *
     * @param command {@code subjectType} USER ou ASSISTANT_POLICY
     */
    AssignmentView assign(AssignCommand command);

    void unassign(UUID assignmentId);

    /** @param subjectType filtro opcional (com {@code subjectId}) */
    List<AssignmentView> assignments(String subjectType, UUID subjectId);

    /** Alçada efetiva de um sujeito para um credor (perfil resolvido e sua origem). */
    EffectiveAuthorityView effective(String subjectType, UUID subjectId, UUID creditorId);

    /** Alçada efetiva do ator corrente para o credor. */
    EffectiveAuthorityView mine(UUID creditorId);

    /**
     * @param code          ignorado em {@code updateProfile}
     * @param maxAgreementAmount nulo = sem limite próprio
     * @param creditorIds   vazio = todos os credores
     */
    record ProfileCommand(String code, String name, String description, int maxConcessionLevel,
                          BigDecimal maxDiscountShare, int maxInstallments, BigDecimal maxAgreementAmount,
                          BigDecimal minDownPaymentShare, boolean canFormalize, boolean canApprove, int maxRenegotiations,
                          Set<UUID> creditorIds) {
    }

    record ProfileView(UUID id, String code, String name, String description, boolean systemProfile,
                       int maxConcessionLevel, BigDecimal maxDiscountShare, int maxInstallments,
                       BigDecimal maxAgreementAmount, BigDecimal minDownPaymentShare, boolean canFormalize,
                       boolean canApprove, int maxRenegotiations, Set<UUID> creditorIds, long assignments) {
    }

    record AssignCommand(String subjectType, UUID subjectId, UUID creditorId, UUID profileId) {
    }

    record AssignmentView(UUID id, String subjectType, UUID subjectId, UUID creditorId, UUID profileId,
                          String profileCode) {
    }

    /**
     * @param source ASSIGNED_CREDITOR (atribuição do credor), ASSIGNED_ALL (atribuição geral) ou
     *               DEFAULT (perfil padrão do tipo de ator)
     */
    record EffectiveAuthorityView(String subjectType, UUID subjectId, UUID creditorId, String source,
                                  ProfileView profile) {
    }
}
