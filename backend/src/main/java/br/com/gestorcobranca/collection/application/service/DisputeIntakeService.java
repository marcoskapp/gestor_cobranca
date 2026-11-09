package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeOrigin;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Contestação aberta pelo devedor no portal → contestação no collection (ver {@link DisputeIntakeUseCases}). Usa a
 * mesma abertura da contestação manual ({@code DisputeService#openFor}): vínculo dos títulos, caso em
 * {@code DISPUTED}, prazos, auditoria, evento de negócio {@code dispute.opened} ({@code trigger=portal}),
 * distribuição e {@code DisputeStatusChanged}.
 */
@Service
@Transactional
class DisputeIntakeService implements DisputeIntakeUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeIntakeService.class);
    /** Tipos que o devedor escolhe no portal (os demais pedem análise da assessoria). */
    static final Set<DisputeType> PORTAL_TYPES = EnumSet.of(DisputeType.NOT_RECOGNIZED, DisputeType.ALREADY_PAID,
            DisputeType.WRONG_AMOUNT, DisputeType.OTHER);

    private final CollectionCaseRepository cases;
    private final DisputeRepository disputes;
    private final DisputeService disputeService;
    private final CaseAccess access;
    private final CurrentActor currentActor;
    private final CollectionMetrics metrics;

    DisputeIntakeService(CollectionCaseRepository cases, DisputeRepository disputes, DisputeService disputeService,
                         CaseAccess access, CurrentActor currentActor, CollectionMetrics metrics) {
        this.cases = cases;
        this.disputes = disputes;
        this.disputeService = disputeService;
        this.access = access;
        this.currentActor = currentActor;
        this.metrics = metrics;
    }

    @Override
    public PortalDisputeResult openFromPortal(PortalDisputeCommand command) {
        Guard.require(currentActor.get().isSystem(), "dispute.intake_system_only",
                "Entrada de contestação externa só pelo processo do portal");
        Guard.require(command != null && command.requestId() != null && command.debtorId() != null
                && command.creditorId() != null && command.receivedAt() != null, "dispute.intake_invalid",
                "Pedido de contestação incompleto");
        DisputeType type = command.type() == null || command.type().isBlank() ? DisputeType.OTHER
                : CollectionEnums.parse(DisputeType.class, command.type(), "dispute.type_invalid", "Tipo");
        Guard.require(PORTAL_TYPES.contains(type), "dispute.type_invalid", "Tipo não disponível no portal");
        String reference = "portal_request:" + command.requestId();
        access.lockDebtor(command.debtorId());

        List<Dispute> earlier = disputes.findByOriginReference(reference);
        List<UUID> opened = new ArrayList<>(earlier.stream().map(Dispute::getId).toList());
        Set<UUID> casesDone = new java.util.HashSet<>(earlier.stream().map(Dispute::getCaseId).toList());
        List<UUID> existing = new ArrayList<>();
        List<CollectionCase> targets = targets(command);
        boolean anyTitles = false;
        for (CollectionCase c : targets) {
            if (casesDone.contains(c.getId())) {
                anyTitles = true;
                continue;
            }
            Optional<Dispute> open = disputeService.openOn(c.getId());
            if (open.isPresent()) {
                existing.add(open.get().getId());
                anyTitles = true;
                continue;
            }
            if (!disputeService.hasTitlesToDispute(c)) {
                continue;
            }
            anyTitles = true;
            Dispute d = disputeService.openFor(c, new OpenDisputeCommand(Set.of(), type.name(),
                    DisputeOrigin.DEBTOR.name(), description(command), true, null), null, reference,
                    command.receivedAt());
            opened.add(d.getId());
        }
        String outcome = !opened.isEmpty() ? "OPENED" : !existing.isEmpty() ? "ALREADY_OPEN"
                : targets.isEmpty() ? "NO_CASE" : anyTitles ? "ALREADY_OPEN" : "NO_TITLES";
        metrics.portalDisputeIntake(outcome);
        LOG.atInfo().addKeyValue("requestId", command.requestId()).addKeyValue("outcome", outcome)
                .addKeyValue("opened", opened.size()).log("Contestação do portal registrada no collection");
        return new PortalDisputeResult(outcome, List.copyOf(opened), List.copyOf(existing));
    }

    private List<CollectionCase> targets(PortalDisputeCommand command) {
        return cases.findActiveByDebtorAndCreditor(command.debtorId(), command.creditorId()).stream()
                .filter(c -> command.portfolioIds().contains(c.getPortfolioId()))
                .sorted(java.util.Comparator.comparing(CollectionCase::getId)).toList();
    }

    /**
     * Texto da contestação: origem e protocolo do pedido. O relato do devedor não é copiado (minimização): fica no
     * pedido do portal, aberto com a permissão e o log de acesso de lá.
     */
    static String description(PortalDisputeCommand command) {
        String base = "Contestação registrada pelo devedor no portal de autonegociação"
                + (command.protocol() == null ? "." : " (protocolo " + command.protocol() + ").");
        return command.withNote()
                ? base + " O devedor deixou um relato no pedido do portal: veja em Portal → Pedidos de atendimento."
                : base + " O devedor não deixou relato; peça os detalhes no contato.";
    }

}
