package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.InsolvencyKind;
import br.com.gestorcobranca.debt.domain.model.InsolvencyProceeding;
import br.com.gestorcobranca.debt.domain.model.InsolvencyStatus;
import br.com.gestorcobranca.debt.domain.model.PersonType;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.InsolvencyProceedingRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.CnjNumber;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.debt.DebtorInsolvencyChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Processos de insolvência do devedor PJ (cobrança B2B §7). Toda mudança grava auditoria ({@code insolvency.*}, com
 * motivo fixo — o texto livre fica só no processo, que a anonimização neutraliza), evento de negócio (sem texto livre)
 * e {@link DebtorInsolvencyChanged} na mesma transação. O processo vale para a <strong>empresa inteira</strong>: matriz
 * e filiais (mesma raiz do CNPJ) ficam suspensas juntas. Comandos exigem escopo sobre todos os credores de todas as
 * unidades ({@link DebtorVisibility#requireFullScope}): a cobrança é suspensa ou retomada em todos eles.
 */
@Service
@Transactional
class InsolvencyService implements InsolvencyUseCases {

    /** Motivos fixos na trilha: o texto livre (que pode citar pessoas) fica só no processo, anonimizável. */
    private static final String AUDIT_REASON_REGISTERED = "Processo registrado com a origem da informação";
    private static final String AUDIT_REASON_CONVERTED = "Convolação em falência registrada com motivo";
    private static final String AUDIT_REASON_ENDED = "Encerramento registrado com motivo";

    private final InsolvencyProceedingRepository proceedings;
    private final DebtorRepository debtors;
    private final DebtorVisibility visibility;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    InsolvencyService(InsolvencyProceedingRepository proceedings, DebtorRepository debtors, DebtorVisibility visibility,
                      CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                      ApplicationEventPublisher events, MeterRegistry meterRegistry, Clock clock) {
        this.proceedings = proceedings;
        this.debtors = debtors;
        this.visibility = visibility;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public ProceedingView register(RegisterProceedingCommand command) {
        Guard.require(command != null && command.debtorId() != null, "insolvency.debtor_required",
                "Devedor obrigatório");
        Debtor debtor = visibility.requireFullScope(command.debtorId());
        Guard.require(debtor.getPersonType() == PersonType.COMPANY, "insolvency.debtor_not_company",
                "Recuperação judicial e falência são registradas só para empresa (CNPJ)");
        List<UUID> units = units(debtor);
        units.forEach(visibility::requireFullScope);
        // serializa registros simultâneos em unidades da mesma empresa (o índice único é por devedor); ordem de id
        units.stream().sorted().forEach(debtors::findByIdForUpdate);
        Guard.requireState(!debtor.isAnonymized(), "insolvency.debtor_anonymized", "Devedor anonimizado");
        InsolvencyKind kind = parseKind(command.kind());
        Guard.require(command.note() != null && !command.note().isBlank(), "insolvency.note_required",
                "Informe a origem da informação (ex.: edital, comunicado do administrador judicial, consulta ao processo)");
        CnjNumber cnj = CnjNumber.parse(command.caseNumber(), "insolvency");
        if (proceedings.existsByDebtorIdInAndStatus(units, InsolvencyStatus.ACTIVE)) {
            throw new ConflictException("insolvency.already_active",
                    "A empresa (matriz ou filial) já tem processo em curso; encerre-o ou registre a convolação em falência");
        }
        InsolvencyProceeding p = proceedings.save(new InsolvencyProceeding(debtor.getId(), kind, cnj, command.court(),
                command.filedOn(), command.note(), today()));
        auditTrail.record(AuditEntry.of("insolvency.registered", "insolvency_proceeding", p.getId())
                .created("debtorId", p.getDebtorId())
                .created("kind", p.getKind())
                .created("caseNumber", cnj.formatted())
                .created("court", p.getCourt())
                .created("filedOn", p.getFiledOn())
                .created("units", units.size())
                .reason(AUDIT_REASON_REGISTERED));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.DEBTOR_INSOLVENCY_REGISTERED)
                .debtor(p.getDebtorId()).reference("insolvency_proceeding", p.getId())
                .attr("kind", p.getKind())
                .attr("daysSinceFiled", ChronoUnit.DAYS.between(p.getFiledOn(), today())));
        counter("registered", p.getKind());
        publish(p);
        return view(p);
    }

    @Override
    public ProceedingView convertToBankruptcy(UUID proceedingId, LocalDate convertedOn, String reason) {
        InsolvencyProceeding p = loadForCommand(proceedingId);
        Guard.require(reason != null && !reason.isBlank(), "insolvency.conversion_reason_required",
                "Informe o motivo da convolação");
        InsolvencyKind previous = p.convertToBankruptcy(convertedOn, today());
        proceedings.save(p);
        auditTrail.record(AuditEntry.of("insolvency.converted", "insolvency_proceeding", p.getId())
                .change("kind", previous, p.getKind())
                .change("convertedOn", null, p.getConvertedOn())
                .meta("debtorId", p.getDebtorId())
                .reason(AUDIT_REASON_CONVERTED));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.DEBTOR_INSOLVENCY_CONVERTED)
                .debtor(p.getDebtorId()).reference("insolvency_proceeding", p.getId())
                .attr("from", previous).attr("to", p.getKind())
                .attr("daysInRecovery", ChronoUnit.DAYS.between(p.getFiledOn(), p.getConvertedOn())));
        counter("converted", p.getKind());
        publish(p);
        return view(p);
    }

    @Override
    public ProceedingView end(UUID proceedingId, LocalDate endedOn, String reason) {
        InsolvencyProceeding p = loadForCommand(proceedingId);
        p.end(endedOn, reason, today());
        proceedings.save(p);
        auditTrail.record(AuditEntry.of("insolvency.ended", "insolvency_proceeding", p.getId())
                .change("status", InsolvencyStatus.ACTIVE, InsolvencyStatus.ENDED)
                .change("endedOn", null, p.getEndedOn())
                .meta("debtorId", p.getDebtorId())
                .meta("kind", p.getKind())
                .reason(AUDIT_REASON_ENDED));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.DEBTOR_INSOLVENCY_ENDED)
                .debtor(p.getDebtorId()).reference("insolvency_proceeding", p.getId())
                .attr("kind", p.getKind())
                .attr("activeDays", ChronoUnit.DAYS.between(p.getFiledOn(), p.getEndedOn())));
        counter("ended", p.getKind());
        publish(p);
        return view(p);
    }

    @Override
    @Transactional(readOnly = true)
    public DebtorInsolvency ofDebtor(UUID debtorId) {
        Guard.require(debtorId != null, "insolvency.debtor_required", "Devedor obrigatório");
        Debtor debtor = visibility.requireVisible(debtorId);
        boolean company = debtor.getPersonType() == PersonType.COMPANY;
        List<UUID> units = units(debtor);
        // nota e motivo (texto livre) só para quem vê o cadastro do devedor; quem só vê o caso recebe o aviso
        boolean freeText = currentActor.get().has(Permission.DEBTOR_VIEW) || currentActor.get().has(Permission.DEBTOR_MANAGE);
        // unidade fora do escopo do ator: o processo aparece (suspende a empresa), sem o id da unidade nem texto livre
        Set<UUID> visibleUnits = new HashSet<>();
        visibility.filterVisible(debtors.findAllByIdIn(units)).forEach(d -> visibleUnits.add(d.getId()));
        visibleUnits.add(debtorId);
        List<ProceedingView> history = proceedings.findByDebtorIdInOrderByFiledOnDescCreatedAtDesc(units).stream()
                .map(p -> view(p, visibleUnits.contains(p.getDebtorId()), freeText)).toList();
        ProceedingView active = history.stream().filter(v -> InsolvencyStatus.ACTIVE.name().equals(v.status()))
                .findFirst().orElse(null);
        boolean canManage = company && currentActor.get().has(Permission.DEBTOR_MANAGE)
                && units.stream().allMatch(visibility::hasFullScope);
        return new DebtorInsolvency(debtorId, company, active, history, canManage);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUnderInsolvency(UUID debtorId) {
        if (debtorId == null) {
            return false;
        }
        return debtors.findById(debtorId)
                .map(d -> proceedings.existsByDebtorIdInAndStatus(units(d), InsolvencyStatus.ACTIVE))
                .orElse(false);
    }

    /** Anonimização do devedor: neutraliza o texto livre dos processos (consumidor de {@code DebtorAnonymized}). */
    void forgetFreeText(UUID debtorId) {
        List<InsolvencyProceeding> found = proceedings.findByDebtorIdOrderByFiledOnDescCreatedAtDesc(debtorId);
        found.forEach(p -> {
            p.forgetFreeText();
            proceedings.save(p);
        });
        if (!found.isEmpty()) {
            auditTrail.record(AuditEntry.of("insolvency.anonymized", "debtor", debtorId).meta("proceedings", found.size()));
        }
    }

    /** Matriz e filiais da mesma empresa (mesma raiz do CNPJ), incluindo o próprio devedor. */
    private List<UUID> units(Debtor debtor) {
        if (debtor.getDocumentRootHash() == null) {
            return List.of(debtor.getId());
        }
        List<UUID> ids = new ArrayList<>(debtors.findIdsByDocumentRootHash(debtor.getDocumentRootHash()));
        if (!ids.contains(debtor.getId())) {
            ids.add(debtor.getId());
        }
        return ids;
    }

    private InsolvencyProceeding loadForCommand(UUID proceedingId) {
        InsolvencyProceeding p = proceedings.findById(proceedingId)
                .orElseThrow(() -> ResourceNotFoundException.of("insolvency_proceeding", proceedingId));
        Debtor debtor = visibility.requireFullScope(p.getDebtorId());
        units(debtor).forEach(visibility::requireFullScope);
        return p;
    }

    /** Uma publicação por unidade da empresa: o collection reavalia os casos de matriz e filiais. */
    private void publish(InsolvencyProceeding p) {
        Debtor debtor = debtors.findById(p.getDebtorId())
                .orElseThrow(() -> ResourceNotFoundException.of("debtor", p.getDebtorId()));
        List<UUID> units = units(debtor);
        boolean active = proceedings.existsByDebtorIdInAndStatus(units, InsolvencyStatus.ACTIVE);
        Instant now = Instant.now(clock);
        for (UUID unit : units) {
            events.publishEvent(new DebtorInsolvencyChanged(Ids.newId(), currentActor.tenantId(), now, unit, active,
                    p.getKind().name()));
        }
    }

    /** Métrica sem devedor: só a ação e o tipo do processo. */
    private void counter(String action, InsolvencyKind kind) {
        meterRegistry.counter("debt.insolvency.changes", "action", action, "kind", kind.name()).increment();
    }

    private static InsolvencyKind parseKind(String kind) {
        try {
            return InsolvencyKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessRuleException("insolvency.kind_invalid", "Tipo de processo inválido");
        }
    }

    private LocalDate today() {
        return DebtViews.today(clock);
    }

    private static ProceedingView view(InsolvencyProceeding p) {
        return view(p, true, true);
    }

    private static ProceedingView view(InsolvencyProceeding p, boolean visibleUnit, boolean freeText) {
        boolean text = visibleUnit && freeText;
        return new ProceedingView(p.getId(), visibleUnit ? p.getDebtorId() : null, p.getKind().name(),
                p.cnj().formatted(), p.getCourt(), p.getFiledOn(), p.since(),
                p.getConvertedFrom() == null ? null : p.getConvertedFrom().name(), p.getConvertedOn(),
                p.getStatus().name(), p.getEndedOn(), text ? p.getEndReason() : null, text ? p.getNote() : null);
    }
}
