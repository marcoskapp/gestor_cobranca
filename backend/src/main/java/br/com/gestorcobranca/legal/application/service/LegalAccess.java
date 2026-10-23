package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.LawFirm;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.legal.domain.repository.LawFirmRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Escopo do jurídico, em duas camadas: (1) escopo por credor ({@link AccessScope}: 404 na
 * leitura, 403 nos comandos); (2) usuário membro de escritório parceiro só alcança casos
 * jurídicos atribuídos ao seu escritório — fora deles, 404 e {@code SCOPE_DENIED} no log de
 * acesso; com o escritório inativo, nenhum. Configurações da assessoria (parâmetros, regras,
 * escritórios, suspensões) são exclusivas de usuários internos.
 */
@Component
class LegalAccess {

    static final String MATTER = "legal_matter";

    private final LegalMatterRepository matters;
    private final LawFirmMemberRepository members;
    private final LawFirmRepository firms;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final LegalMetrics metrics;

    LegalAccess(LegalMatterRepository matters, LawFirmMemberRepository members, LawFirmRepository firms,
                AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger, LegalMetrics metrics) {
        this.matters = matters;
        this.members = members;
        this.firms = firms;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
    }

    /**
     * @param lawFirmId  escritório do ator (nulo = usuário interno, sistema ou integração)
     * @param firmActive escritório ativo
     */
    record ActorScope(UUID lawFirmId, boolean firmActive) {

        static final ActorScope INTERNAL = new ActorScope(null, true);

        boolean external() {
            return lawFirmId != null;
        }
    }

    ActorScope scope() {
        Actor actor = currentActor.get();
        if (!actor.isUser()) {
            return ActorScope.INTERNAL;
        }
        return members.findByUserIdAndActiveTrue(actor.id())
                .map(m -> new ActorScope(m.getLawFirmId(), firms.findById(m.getLawFirmId()).map(LawFirm::isActive)
                        .orElse(false)))
                .orElse(ActorScope.INTERNAL);
    }

    LegalMatter visible(UUID matterId) {
        LegalMatter matter = matters.findById(matterId).orElseThrow(() -> ResourceNotFoundException.of(MATTER, matterId));
        requireVisible(matter);
        return matter;
    }

    /** Para comandos: trava o caso jurídico e exige o credor no escopo de comando. */
    LegalMatter forCommand(UUID matterId) {
        LegalMatter matter = matters.findByIdForUpdate(matterId)
                .orElseThrow(() -> ResourceNotFoundException.of(MATTER, matterId));
        requireVisible(matter);
        accessScope.requireCreditor(matter.getCreditorId());
        return matter;
    }

    void requireVisible(LegalMatter matter) {
        accessScope.requireVisible(MATTER, matter.getId(), matter.getCreditorId());
        ActorScope scope = scope();
        if (scope.external() && (!scope.firmActive() || !scope.lawFirmId().equals(matter.getLawFirmId()))) {
            deny(MATTER, matter.getId());
            throw new ResourceNotFoundException(MATTER, matter.getId());
        }
    }

    /** Configurações da assessoria: usuário interno (membros de escritório recebem 403). */
    void requireInternal(String resource) {
        if (scope().external()) {
            deny(resource, null);
            throw new ScopeAccessDeniedException();
        }
    }

    void requireCreditor(UUID creditorId) {
        accessScope.requireCreditor(creditorId);
    }

    void requireAllCreditors(String resource) {
        accessScope.requireAllCreditors(resource);
    }

    /** Credores permitidos para listagens (nulo = todos). */
    Set<UUID> creditorFilter() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
    }

    /**
     * Restrição de escritório para listagens: vazio = sem restrição; com escritório inativo, um id
     * que não corresponde a nenhum caso (nenhum resultado).
     */
    Optional<UUID> firmFilter() {
        ActorScope scope = scope();
        if (!scope.external()) {
            return Optional.empty();
        }
        return Optional.of(scope.firmActive() ? scope.lawFirmId() : new UUID(0L, 0L));
    }

    Actor actor() {
        return currentActor.get();
    }

    private void deny(String resource, UUID id) {
        metrics.scopeDenied(resource);
        accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, currentActor.get(),
                id == null ? Map.of("resource", resource, "scope", "law_firm")
                        : Map.of("resource", resource, "resourceId", id.toString(), "scope", "law_firm")));
    }
}
