package br.com.gestorcobranca.negotiation.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

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

    /** Limite de resultados por página de {@link #assignableUsers}. */
    int ASSIGNABLE_MAX_PAGE_SIZE = 50;

    /** Limite de ids na resolução de nomes já escolhidos em {@link #assignableUsers}. */
    int ASSIGNABLE_MAX_IDS = 50;

    /**
     * Usuários que podem receber alçada para o credor ({@code creditorId} nulo = todos), pela mesma regra que
     * {@link #assign} aplica ao gravar: habilitado (ativo ou aguardando ativação — a alçada pode ser preparada no
     * onboarding), nunca o próprio ator e, na atribuição por credor, com acesso a esse credor. Não exige
     * {@code NEGOTIATION_PROPOSE}: a permissão é dada no perfil de acesso, que pode vir depois; sem ela a alçada
     * não tem efeito. Exige o mesmo escopo do ator que a atribuição (credor no escopo; geral = todos os credores).
     * Só id e nome; cada consulta vai para o log de acesso ({@code USER_DIRECTORY_QUERIED}).
     *
     * @param query trecho do nome (sem diferenciar maiúsculas e acentos); vazio lista todos
     * @param ids   quando informado, só esses ids (até {@value #ASSIGNABLE_MAX_IDS}) entre os elegíveis
     */
    PageResponse<AssignableUserView> assignableUsers(UUID creditorId, String query, Set<UUID> ids, PageQuery page);

    void unassign(UUID assignmentId);

    /** @param subjectType filtro opcional (com {@code subjectId}) */
    List<AssignmentView> assignments(String subjectType, UUID subjectId);

    /** Alçada efetiva de um sujeito para um credor (perfil resolvido e sua origem). */
    EffectiveAuthorityView effective(String subjectType, UUID subjectId, UUID creditorId);

    /** Alçada efetiva do ator corrente para o credor. */
    EffectiveAuthorityView mine(UUID creditorId);

    /** Leitura mínima de quem pode receber alçada: id e nome de exibição. */
    record AssignableUserView(UUID id, String name) {
    }

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
