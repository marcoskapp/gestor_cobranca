package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.authority.AssignmentSubject;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityAssignment;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;
import br.com.gestorcobranca.negotiation.domain.model.authority.StandardAuthorityProfiles;
import br.com.gestorcobranca.negotiation.domain.repository.AuthorityRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Actor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolve o perfil de alçada de um ator para um credor:
 * <ol>
 *   <li>atribuição do sujeito para o credor;</li>
 *   <li>atribuição geral do sujeito (todos os credores);</li>
 *   <li>perfil padrão do tipo de ator ({@code JUNIOR} para usuários, {@code ASSISTENTE_INICIAL}
 *       para o assistente, {@code PORTAL} para o devedor no portal).</li>
 * </ol>
 * O perfil precisa valer para o credor; se não valer, passa-se ao próximo passo. O devedor
 * autenticado no portal usa a alçada do <em>canal</em> ({@link AssignmentSubject#PORTAL}, id
 * {@link AssignmentSubject#PORTAL_CHANNEL_ID}), nunca uma alçada pessoal. Sistema e integração não
 * têm alçada (as campanhas receberão sujeito próprio).
 */
@Component
class AuthorityResolver {

    enum Source { ASSIGNED_CREDITOR, ASSIGNED_ALL, DEFAULT }

    record Resolution(AuthorityProfile profile, Source source) {
    }

    private final AuthorityRepository authority;

    AuthorityResolver(AuthorityRepository authority) {
        this.authority = authority;
    }

    Resolution resolve(Actor actor, UUID creditorId) {
        AssignmentSubject subject = switch (actor.type()) {
            case USER -> AssignmentSubject.USER;
            case ASSISTANT -> AssignmentSubject.ASSISTANT_POLICY;
            case DEBTOR -> AssignmentSubject.PORTAL;
            default -> throw new BusinessRuleException("authority.actor_not_supported",
                    "Ator do tipo " + actor.type() + " não tem alçada de negociação");
        };
        return resolve(subject, subject == AssignmentSubject.PORTAL ? AssignmentSubject.PORTAL_CHANNEL_ID : actor.id(),
                creditorId);
    }

    Resolution resolve(AssignmentSubject subject, UUID subjectId, UUID creditorId) {
        Optional<Resolution> assigned = assigned(subject, subjectId, creditorId, creditorId, Source.ASSIGNED_CREDITOR)
                .or(() -> assigned(subject, subjectId, null, creditorId, Source.ASSIGNED_ALL));
        if (assigned.isPresent()) {
            return assigned.get();
        }
        String fallback = switch (subject) {
            case ASSISTANT_POLICY -> StandardAuthorityProfiles.DEFAULT_FOR_ASSISTANT;
            case PORTAL -> StandardAuthorityProfiles.DEFAULT_FOR_PORTAL;
            case USER -> StandardAuthorityProfiles.DEFAULT_FOR_USERS;
        };
        return authority.findProfileByCode(fallback).filter(p -> p.appliesTo(creditorId))
                .map(p -> new Resolution(p, Source.DEFAULT))
                .orElseThrow(() -> new BusinessRuleException("authority.not_configured",
                        "Nenhum perfil de alçada aplicável ao ator para este credor"));
    }

    /**
     * @param assignmentCreditor credor da atribuição procurada (nulo = geral)
     * @param targetCreditor     credor da negociação, ao qual o perfil precisa se aplicar
     */
    private Optional<Resolution> assigned(AssignmentSubject subject, UUID subjectId, UUID assignmentCreditor,
                                          UUID targetCreditor, Source source) {
        return authority.findAssignment(subject, subjectId, assignmentCreditor).map(AuthorityAssignment::getProfileId)
                .flatMap(authority::findProfile).filter(p -> p.appliesTo(targetCreditor))
                .map(p -> new Resolution(p, source));
    }
}
