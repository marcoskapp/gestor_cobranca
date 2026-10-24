package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases;
import br.com.gestorcobranca.settlement.domain.model.CreditorTransfer;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class TransferService implements TransferUseCases {

    static final String RESOURCE = "settlement_transfer";

    private final SettlementStatementRepository statements;
    private final TransferEvidenceStore evidenceStore;
    private final FileStorage storage;
    private final IdempotencyService idempotency;
    private final SettlementAccess access;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final SettlementMetrics metrics;
    private final Clock clock;

    TransferService(SettlementStatementRepository statements, TransferEvidenceStore evidenceStore, FileStorage storage,
                    IdempotencyService idempotency, SettlementAccess access, AccessScope accessScope,
                    CurrentActor currentActor, AccessLogger accessLogger, AuditTrail auditTrail,
                    BusinessEventLogger businessEvents, SettlementMetrics metrics, Clock clock) {
        this.statements = statements;
        this.evidenceStore = evidenceStore;
        this.storage = storage;
        this.idempotency = idempotency;
        this.access = access;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public TransferView register(TransferCommand c, EvidenceUpload evidence, String idempotencyKey) {
        accessScope.requirePermission(Permission.SETTLEMENT_TRANSFER);
        Guard.require(c.statementId() != null, "transfer.statement_required", "Demonstrativo obrigatório");
        Guard.require(c.amount() != null && c.amount().signum() > 0 && c.amount().scale() <= 2,
                "transfer.amount_invalid", "Valor do repasse deve ser positivo, com até 2 casas decimais");
        byte[] content = evidenceStore.read(evidence);
        UUID id = idempotency.execute("settlement.transfer", idempotencyKey, hash(c, content), () -> {
            SettlementStatement s = statements.findByIdForUpdate(c.statementId())
                    .orElseThrow(() -> new ResourceNotFoundException(SettlementStatementService.RESOURCE, c.statementId()));
            accessScope.requireVisible(SettlementStatementService.RESOURCE, s.getId(), s.getCreditorId());
            accessScope.requireCreditor(s.getCreditorId());
            CreditorTransfer.Evidence stored = evidenceStore.store(evidence.fileName(), evidence.contentType(), content);
            CreditorTransfer transfer = statements.save(new CreditorTransfer(s, Money.of(c.amount()), c.transferredOn(),
                    today(), c.bankReference(), c.note(), stored, currentActor.actorId()));
            statements.save(s);
            auditTrail.record(AuditEntry.of("settlement_transfer.registered", RESOURCE, transfer.getId())
                    .creditor(s.getCreditorId()).created("statementId", s.getId())
                    .created("direction", transfer.getDirection()).created("amount", transfer.getAmount().amount())
                    .created("transferredOn", transfer.getTransferredOn())
                    .created("evidenceSha256", transfer.getEvidenceSha256()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.SETTLEMENT_TRANSFER_RECORDED).creditor(s.getCreditorId())
                    .reference(RESOURCE, transfer.getId()).amount(transfer.getAmount())
                    .attr("statementId", s.getId()).attr("direction", transfer.getDirection())
                    .attr("outstandingAfter", s.outstanding().amount())
                    .attr("daysAfterApproval", ChronoUnit.DAYS.between(
                            s.getApprovedAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(), transfer.getTransferredOn())));
            metrics.transfer(transfer.getDirection().name(), "registered");
            return transfer.getId();
        });
        return SettlementViews.transfer(load(id));
    }

    @Override
    public TransferView cancel(UUID transferId, String reason) {
        accessScope.requirePermission(Permission.SETTLEMENT_TRANSFER);
        CreditorTransfer transfer = load(transferId);
        SettlementStatement s = statements.findByIdForUpdate(transfer.getStatementId()).orElseThrow();
        accessScope.requireCreditor(s.getCreditorId());
        transfer.cancel(s, currentActor.actorId(), reason, Instant.now(clock));
        statements.save(transfer);
        statements.save(s);
        auditTrail.record(AuditEntry.of("settlement_transfer.cancelled", RESOURCE, transfer.getId())
                .creditor(s.getCreditorId()).change("status", CreditorTransfer.Status.REGISTERED,
                        CreditorTransfer.Status.CANCELLED).reason(transfer.getCancelReason()));
        metrics.transfer(transfer.getDirection().name(), "cancelled");
        return SettlementViews.transfer(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public TransferView get(UUID transferId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER);
        return SettlementViews.transfer(load(transferId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TransferView> search(UUID creditorId, PageQuery page) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER);
        if (creditorId != null) {
            accessScope.requireCreditor(creditorId);
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        return PageResponse.of(statements.searchTransfers(allowed, creditorId,
                page.toPageable(Sort.by(Sort.Direction.DESC, "transferredOn").and(Sort.by("id")))),
                SettlementViews::transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContent openEvidence(UUID transferId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER);
        CreditorTransfer transfer = load(transferId);
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(), Map.of("resource", RESOURCE,
                "resourceId", transferId.toString(), "category", FileCategory.EVIDENCE.name())));
        return new EvidenceContent(transfer.getEvidenceName(), transfer.getEvidenceType(), transfer.getEvidenceSize(),
                storage.open(transfer.getEvidenceKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BalanceView> balances(UUID creditorId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER);
        Set<UUID> filter;
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
            filter = Set.of(creditorId);
        } else {
            filter = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        }
        Map<UUID, List<StatementBalance>> byCreditor = new LinkedHashMap<>();
        Map<UUID, Money[]> totals = new LinkedHashMap<>();
        for (SettlementStatement s : statements.findClosed(filter)) {
            Money outstanding = s.outstanding();
            if (!outstanding.isPositive()) {
                continue;
            }
            byCreditor.computeIfAbsent(s.getCreditorId(), k -> new ArrayList<>()).add(new StatementBalance(s.getId(),
                    s.getSequenceNumber(), s.getPeriodStart(), s.getPeriodEnd(), s.getStatus().name(),
                    s.getNetTransferAmount().amount(), s.getTransferredAmount().amount(), outstanding.amount()));
            Money[] t = totals.computeIfAbsent(s.getCreditorId(), k -> new Money[]{Money.ZERO, Money.ZERO});
            if (s.getNetTransferAmount().isPositive()) {
                t[0] = t[0].plus(outstanding);
            } else {
                t[1] = t[1].plus(outstanding);
            }
        }
        return byCreditor.entrySet().stream().map(e -> new BalanceView(e.getKey(), totals.get(e.getKey())[0].amount(),
                totals.get(e.getKey())[1].amount(), List.copyOf(e.getValue()))).toList();
    }

    private CreditorTransfer load(UUID id) {
        CreditorTransfer t = statements.findTransfer(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, t.getCreditorId());
        return t;
    }

    private static String hash(TransferCommand c, byte[] evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = c.statementId() + "|" + (c.amount() == null ? "" : c.amount().stripTrailingZeros()
                    .toPlainString()) + "|" + c.transferredOn() + "|" + c.bankReference() + "|" + c.note();
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            digest.update(evidence);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
