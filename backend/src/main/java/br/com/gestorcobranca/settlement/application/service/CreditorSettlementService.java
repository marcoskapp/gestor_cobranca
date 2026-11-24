package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases.PositionView;
import br.com.gestorcobranca.settlement.domain.model.ResponseOrigin;
import br.com.gestorcobranca.settlement.domain.model.SettlementExport;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.StatementCreditorTerm;
import br.com.gestorcobranca.settlement.domain.model.StatementStatus;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Prestação de contas no portal do credor (ADR 0011, fatia 4). O credor vem sempre do ator ({@code CREDITOR}, um único
 * credor); demonstrativo de outro credor ou ainda não enviado responde 404 (sem revelar a existência). As respostas usam
 * as mesmas transições do domínio que a assessoria usa ao registrar a resposta recebida por outro canal, com origem
 * {@code CREDITOR_PORTAL} e o termo (prova) gravado junto.
 */
@Service
@Transactional
class CreditorSettlementService implements CreditorSettlementUseCases {

    private static final String RESOURCE = SettlementStatementService.RESOURCE;
    private static final Set<StatementStatus> VISIBLE = Set.of(StatementStatus.SENT, StatementStatus.ACKNOWLEDGED,
            StatementStatus.CONTESTED);

    private final SettlementStatementRepository statements;
    private final SettlementPositionService positions;
    private final FileStorage storage;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final SettlementMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;

    CreditorSettlementService(SettlementStatementRepository statements, SettlementPositionService positions,
                              FileStorage storage, AccessScope accessScope, CurrentActor currentActor,
                              AccessLogger accessLogger, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                              SettlementMetrics metrics, JsonCodec json, Clock clock) {
        this.statements = statements;
        this.positions = positions;
        this.storage = storage;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorStatementSummary> statements() {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        return statements.findByCreditorIdOrderByPeriodStartDesc(creditorId).stream()
                .filter(s -> VISIBLE.contains(s.getStatus()))
                .map(CreditorSettlementService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorStatementDetail statement(UUID statementId) {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        return detail(load(statementId, creditorId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorOpenPeriod openPeriod() {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        PositionView p = positions.positionWithoutAccessCheck(creditorId);
        return new CreditorOpenPeriod(p.periodStart(), p.asOf(), p.expectedPeriodEnd(), p.totals().recovered(),
                p.totals().netTransfer(), p.direction(), p.calculatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorFileContent download(UUID statementId, UUID fileId) {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        SettlementStatement s = load(statementId, creditorId, false);
        SettlementExport export = statements.findExport(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("statement_export", fileId));
        if (!export.getStatementId().equals(s.getId())) {
            // arquivo de outro demonstrativo: 404 igual, com SCOPE_DENIED se for de outro credor (RS-06)
            statements.findById(export.getStatementId()).ifPresent(other ->
                    accessScope.requireVisible("statement_export", fileId, other.getCreditorId()));
            throw new ResourceNotFoundException("statement_export", fileId);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", "statement_export", "resourceId", fileId.toString(),
                        "category", FileCategory.SETTLEMENT_STATEMENT.name(), "origin", "creditor_portal")));
        return new CreditorFileContent(export.getFileName(), export.getContentType(), export.getFileSize(),
                export.getSha256(), storage.open(export.getFileKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireCanRespond(UUID statementId) {
        load(statementId, creditor(Permission.CREDITOR_STATEMENT_RESPOND), false);
    }

    @Override
    public CreditorStatementDetail acknowledge(UUID statementId, CreditorResponseCommand c) {
        UUID creditorId = creditor(Permission.CREDITOR_STATEMENT_RESPOND);
        Guard.require(c != null && c.mfaVerifiedAt() != null, "statement.mfa_required",
                "Confirme com o código do autenticador");
        SettlementStatement s = load(statementId, creditorId, true);
        List<SettlementExport> files = requireSameFiles(s, c.fileSha256());
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock);
        StatementStatus previous = s.getStatus();
        s.acknowledge(actor.id(), ResponseOrigin.CREDITOR_PORTAL, c.note(), now);
        statements.save(s);
        StatementCreditorTerm term = term(s, "ACKNOWLEDGED", actor, now, c, files);
        auditTrail.record(AuditEntry.of("settlement_statement.acknowledged", RESOURCE, s.getId())
                .creditor(s.getCreditorId()).change("status", previous, s.getStatus())
                .created("origin", ResponseOrigin.CREDITOR_PORTAL).created("termId", term.getId()).created("termSha256", term.getTermSha256()));
        businessEvents.log(event(BusinessEventTypes.SETTLEMENT_ACKNOWLEDGED, s)
                .attr("afterContestation", previous == StatementStatus.CONTESTED)
                .attr("daysAfterSent", ChronoUnit.DAYS.between(s.getSentAt(), s.getCreditorResponseAt())));
        metrics.statementTransition(s.getStatus().name());
        return detail(s);
    }

    @Override
    public CreditorStatementDetail contest(UUID statementId, CreditorResponseCommand c) {
        UUID creditorId = creditor(Permission.CREDITOR_STATEMENT_RESPOND);
        Guard.require(c != null && c.note() != null && !c.note().isBlank(), "statement.contest_reason_required",
                "Informe o motivo da contestação");
        SettlementStatement s = load(statementId, creditorId, true);
        List<SettlementExport> files = statements.findExports(s.getId());
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock);
        StatementStatus previous = s.getStatus();
        s.contest(actor.id(), ResponseOrigin.CREDITOR_PORTAL, c.note(), now);
        statements.save(s);
        StatementCreditorTerm term = term(s, "CONTESTED", actor, now, c, files);
        auditTrail.record(AuditEntry.of("settlement_statement.contested", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", previous, s.getStatus()).reason(s.getCreditorResponseNote())
                .created("origin", ResponseOrigin.CREDITOR_PORTAL).created("termId", term.getId()).created("termSha256", term.getTermSha256()));
        businessEvents.log(event(BusinessEventTypes.SETTLEMENT_CONTESTED, s).amount(s.getNetTransferAmount())
                .attr("daysAfterSent", ChronoUnit.DAYS.between(s.getSentAt(), s.getCreditorResponseAt())));
        metrics.statementTransition(s.getStatus().name());
        return detail(s);
    }

    /** Só a pessoa do credor, com a permissão do portal; devolve o único credor do ator. */
    private UUID creditor(Permission permission) {
        Actor actor = currentActor.get();
        if (!actor.isCreditor()) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(permission);
        return actor.creditorScope().creditorIds().iterator().next();
    }

    /** Demonstrativo do credor do ator e já enviado; qualquer outro caso é 404 (anti-enumeração). */
    private SettlementStatement load(UUID id, UUID creditorId, boolean forUpdate) {
        SettlementStatement s = (forUpdate ? statements.findByIdForUpdate(id) : statements.findById(id))
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        // outro credor: SCOPE_DENIED no log de acesso e 404 igual (RS-06)
        accessScope.requireVisible(RESOURCE, id, s.getCreditorId());
        if (!s.getCreditorId().equals(creditorId) || !VISIBLE.contains(s.getStatus())) {
            throw new ResourceNotFoundException(RESOURCE, id);
        }
        return s;
    }

    /** Os arquivos que a pessoa viu precisam ser exatamente os atuais (senão ela aceitaria outro conteúdo). */
    private List<SettlementExport> requireSameFiles(SettlementStatement s, List<String> seen) {
        List<SettlementExport> files = statements.findExports(s.getId());
        Set<String> current = new TreeSet<>(files.stream().map(SettlementExport::getSha256).toList());
        Set<String> shown = new TreeSet<>(seen == null ? List.of()
                : seen.stream().filter(h -> h != null && !h.isBlank()).map(String::toLowerCase).toList());
        if (!current.equals(shown)) {
            throw new ConflictException("statement.files_changed",
                    "Os arquivos do demonstrativo mudaram desde que você abriu a página. Recarregue e confira de novo.");
        }
        return files;
    }

    private StatementCreditorTerm term(SettlementStatement s, String decision, Actor actor, Instant now,
                                       CreditorResponseCommand c, List<SettlementExport> files) {
        List<Map<String, Object>> fileList = files.stream()
                .sorted(Comparator.comparing(SettlementExport::getSha256))
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.getId().toString());
                    m.put("fileName", f.getFileName());
                    m.put("format", f.getFormat().name());
                    m.put("sha256", f.getSha256());
                    return m;
                }).toList();
        Instant recordedAt = now.truncatedTo(ChronoUnit.MICROS);
        Instant mfaAt = c.mfaVerifiedAt() == null ? null : c.mfaVerifiedAt().truncatedTo(ChronoUnit.MICROS);
        String note = s.getCreditorResponseNote();
        String canonical = StatementTermDigest.canonical(s.getTenantId(), s.getId(), decision, actor.id(),
                actor.sessionId(), recordedAt, mfaAt, s.getNetTransferAmount().amount(), note,
                fileList.stream().map(m -> (String) m.get("sha256")).toList());
        return statements.save(new StatementCreditorTerm(s.getId(), decision, actor.id(), actor.sessionId(), recordedAt,
                c.clientIp(), mfaAt, note, json.toJson(fileList), StatementTermDigest.sha256(canonical)));
    }

    private BusinessEvent event(String type, SettlementStatement s) {
        return BusinessEvent.of(type).creditor(s.getCreditorId()).reference(RESOURCE, s.getId())
                .attr("sequenceNumber", s.getSequenceNumber()).attr("periodStart", s.getPeriodStart().toString())
                .attr("periodEnd", s.getPeriodEnd().toString()).attr("origin", "creditor_portal");
    }

    private CreditorStatementDetail detail(SettlementStatement s) {
        SettlementStatement.Totals t = s.totals();
        CreditorTotals totals = new CreditorTotals(t.receivedAgency().amount(), t.receivedCreditor().amount(),
                t.reversed().amount(), t.recovered().amount(), t.agencyHonorarium().amount(), t.commission().amount(),
                t.fixedFee().amount(), t.expenses().amount(), t.adjustments().amount(), t.netTransfer().amount());
        List<CreditorStatementFile> files = statements.findExports(s.getId()).stream()
                .sorted(Comparator.comparing(SettlementExport::getCreatedAt))
                .map(e -> new CreditorStatementFile(e.getId(), e.getFormat().name(), e.getFileName(), e.getFileSize(),
                        e.getSha256(), e.getCreatedAt()))
                .toList();
        boolean inPortal = s.getCreditorResponseOrigin() == ResponseOrigin.CREDITOR_PORTAL;
        // a observação registrada pela assessoria é texto interno (decisão 1421): ao credor, só a que ele mesmo escreveu
        return new CreditorStatementDetail(summary(s), totals, files, s.getTransferredAmount().amount(),
                s.outstanding().amount(), inPortal ? s.getCreditorResponseNote() : null, inPortal);
    }

    private static CreditorStatementSummary summary(SettlementStatement s) {
        Money net = s.getNetTransferAmount();
        String direction = net.isPositive() ? "AGENCY_TO_CREDITOR" : net.isZero() ? "NONE" : "CREDITOR_TO_AGENCY";
        return new CreditorStatementSummary(s.getId(), s.getSequenceNumber(), s.getPeriodStart(), s.getPeriodEnd(),
                s.getStatus().name(), s.totals().recovered().amount(), net.amount(), direction, s.getSentAt(),
                s.getCreditorResponseAt());
    }
}
