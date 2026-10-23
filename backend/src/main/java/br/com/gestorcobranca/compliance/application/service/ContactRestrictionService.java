package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.ComplianceProperties;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.FileContent;
import br.com.gestorcobranca.compliance.domain.model.ContactAttempt;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.ContactValueRef;
import br.com.gestorcobranca.compliance.domain.model.ContactValueType;
import br.com.gestorcobranca.compliance.domain.model.RestrictionSource;
import br.com.gestorcobranca.compliance.domain.model.RestrictionStatus;
import br.com.gestorcobranca.compliance.domain.model.RestrictionType;
import br.com.gestorcobranca.compliance.domain.repository.ContactAttemptRepository;
import br.com.gestorcobranca.compliance.domain.repository.ContactRestrictionRepository;
import br.com.gestorcobranca.compliance.domain.repository.RestrictionSearchCriteria;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactFingerprint;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ContactRestrictionService implements ContactRestrictionUseCases {

    /** Tipos de arquivo aceitos como evidência (documentos, imagens, áudio, texto). */
    private static final Set<String> EVIDENCE_TYPES = Set.of("application/pdf", "image/png", "image/jpeg",
            "audio/mpeg", "audio/wav", "audio/ogg", "text/plain", "message/rfc822");
    private static final int KEYWORD_MAX = 30;

    private final ContactRestrictionRepository restrictions;
    private final ContactAttemptRepository attempts;
    private final RestrictionRecorder recorder;
    private final DebtorUseCases debtors;
    private final DebtorContactUseCases debtorContacts;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final FileStorage fileStorage;
    private final StoredFileCleanup fileCleanup;
    private final JsonCodec json;
    private final ComplianceProperties properties;
    private final Clock clock;

    ContactRestrictionService(ContactRestrictionRepository restrictions, ContactAttemptRepository attempts,
                              RestrictionRecorder recorder, DebtorUseCases debtors, DebtorContactUseCases debtorContacts,
                              CreditorUseCases creditors, AccessScope accessScope, CurrentActor currentActor,
                              AccessLogger accessLogger, AuditTrail auditTrail, FileStorage fileStorage,
                              StoredFileCleanup fileCleanup,
                              JsonCodec json, ComplianceProperties properties, Clock clock) {
        this.restrictions = restrictions;
        this.attempts = attempts;
        this.recorder = recorder;
        this.debtors = debtors;
        this.debtorContacts = debtorContacts;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public RestrictionView create(CreateRestrictionCommand command) {
        Guard.require(command.debtorId() != null, "restriction.debtor_required", "Devedor obrigatório");
        RestrictionType type = ComplianceEnums.parse(RestrictionType.class, command.type(), "restriction.type_invalid",
                "Tipo da restrição");
        RestrictionSource source = ComplianceEnums.parse(RestrictionSource.class, command.source(),
                "restriction.source_invalid", "Origem da restrição");
        Guard.require(source != RestrictionSource.PROVIDER_STOP_KEYWORD, "restriction.source_invalid",
                "Opt-out de provedor é registrado pelo canal de comunicação");
        ContactChannel channel = ComplianceEnums.parseOptional(ContactChannel.class, command.channel(),
                "restriction.channel_invalid", "Canal");
        requireCreditorScope(command.creditorId());
        debtors.get(command.debtorId());
        ContactValueRef contact = contactRef(channel, command.contactType(), command.contactValue());
        Instant now = Instant.now(clock);
        Instant startsAt = command.startsAt() == null ? now : command.startsAt();
        ContactRestriction restriction = new ContactRestriction(command.debtorId(), command.creditorId(), channel, type,
                contact, command.reason(), source, startsAt, command.endsAt(), null);
        Guard.require(restriction.getEndsAt() == null || restriction.getEndsAt().isAfter(now), "restriction.period_invalid",
                "Restrição temporária já terminada");
        return RestrictionViews.view(recorder.created(restriction), now);
    }

    @Override
    public RestrictionView revoke(UUID restrictionId, String reason) {
        ContactRestriction restriction = loadVisible(restrictionId);
        requireCreditorScope(restriction.getCreditorId());
        Instant now = Instant.now(clock);
        restriction.revoke(reason, currentActor.actorId(), now);
        return RestrictionViews.view(recorder.revoked(restriction), now);
    }

    @Override
    public RestrictionView attachEvidence(UUID restrictionId, EvidenceUpload upload) {
        ContactRestriction restriction = loadVisible(restrictionId);
        requireCreditorScope(restriction.getCreditorId());
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "restriction.evidence_empty",
                "Arquivo de evidência vazio");
        Guard.require(upload.size() <= properties.evidence().maxSizeBytes(), "restriction.evidence_too_large",
                "Arquivo de evidência excede o tamanho máximo");
        String contentType = upload.contentType() == null ? "" : upload.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        Guard.require(EVIDENCE_TYPES.contains(contentType), "restriction.evidence_type_invalid",
                "Tipo de arquivo não aceito como evidência");
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, upload.fileName(), contentType, upload.content(),
                upload.size());
        fileCleanup.deleteOnRollback(stored.key());
        restriction.attachEvidence(stored.key(), stored.originalName(), contentType, stored.sha256());
        restrictions.save(restriction);
        recordEvidenceAudit(restriction, stored);
        return RestrictionViews.view(restriction, Instant.now(clock));
    }

    private void recordEvidenceAudit(ContactRestriction restriction, StoredFile stored) {
        auditTrail.record(AuditEntry.of("contact_restriction.evidence_attached", "contact_restriction", restriction.getId())
                .creditor(restriction.getCreditorId())
                .created("evidenceFileName", stored.originalName())
                .created("evidenceSha256", stored.sha256())
                .meta("size", stored.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public FileContent openEvidence(UUID restrictionId) {
        ContactRestriction restriction = loadVisible(restrictionId);
        if (restriction.getEvidenceFileKey() == null) {
            throw new ResourceNotFoundException("contact_restriction_evidence", restrictionId);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", "contact_restriction", "resourceId", restrictionId.toString(),
                        "category", FileCategory.EVIDENCE.name())));
        return new FileContent(restriction.getEvidenceFileName(), restriction.getEvidenceContentType(), -1,
                fileStorage.open(restriction.getEvidenceFileKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public RestrictionView get(UUID restrictionId) {
        return RestrictionViews.view(loadVisible(restrictionId), Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RestrictionView> search(RestrictionFilter filter, PageQuery page) {
        if (filter.debtorId() != null) {
            debtors.get(filter.debtorId());
        } else {
            accessScope.requireAllCreditors("contact_restriction");
        }
        if (filter.creditorId() != null) {
            accessScope.requireVisible("creditor", filter.creditorId(), filter.creditorId());
        }
        RestrictionSearchCriteria criteria = new RestrictionSearchCriteria(
                accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null), filter.debtorId(),
                filter.creditorId(),
                ComplianceEnums.parseOptional(RestrictionType.class, filter.type(), "restriction.type_invalid", "Tipo"),
                ComplianceEnums.parseOptional(RestrictionStatus.class, filter.status(), "restriction.status_invalid", "Status"),
                ComplianceEnums.parseOptional(ContactChannel.class, filter.channel(), "restriction.channel_invalid", "Canal"));
        Instant now = Instant.now(clock);
        return PageResponse.of(restrictions.search(criteria, page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))),
                r -> RestrictionViews.view(r, now));
    }

    @Override
    public RestrictionView registerOptOutFromChannel(UUID debtorId, String channel, String contactValue, String keyword) {
        ContactChannel contactChannel = ComplianceEnums.parse(ContactChannel.class, channel, "restriction.channel_invalid",
                "Canal");
        ContactValueType valueType = contactChannel.contactValueType().orElseThrow(() -> new BusinessRuleException(
                "restriction.channel_contact_mismatch", "Canal %s não recebe opt-out por palavra".formatted(contactChannel)));
        Guard.require(contactValue != null && !contactValue.isBlank(), "restriction.contact_required",
                "Contato que pediu o descadastro obrigatório");
        if (debtorId != null) {
            debtors.get(debtorId);
        }
        ContactValueRef contact = fingerprint(valueType, contactValue);
        Instant now = Instant.now(clock);
        for (ContactRestriction existing : restrictions.findActiveByContactValueHash(contact.hash())) {
            if (existing.isOptOutOf(contact.hash(), contactChannel, now)) {
                return RestrictionViews.view(existing, now);
            }
        }
        String word = keyword == null || keyword.isBlank() ? "não informada"
                : keyword.trim().length() > KEYWORD_MAX ? keyword.trim().substring(0, KEYWORD_MAX) : keyword.trim();
        ContactRestriction restriction = new ContactRestriction(debtorId, null, contactChannel, RestrictionType.OPT_OUT,
                contact, "Descadastro recebido pelo canal %s (palavra: %s)".formatted(contactChannel, word),
                RestrictionSource.PROVIDER_STOP_KEYWORD, now, null, null);
        return RestrictionViews.view(recorder.created(restriction), now);
    }

    @Override
    @Transactional(readOnly = true)
    public FileContent exportDebtorEvidence(UUID debtorId) {
        debtors.get(debtorId);
        Instant now = Instant.now(clock);
        List<RestrictionView> visibleRestrictions = restrictions.findByDebtorIdOrderByCreatedAtAsc(debtorId).stream()
                .filter(r -> r.getCreditorId() == null || accessScope.canAccessCreditor(r.getCreditorId()))
                .map(r -> RestrictionViews.view(r, now))
                .toList();
        List<Map<String, Object>> visibleAttempts = attempts.findByDebtorIdOrderByAttemptedAtDesc(debtorId).stream()
                .filter(a -> accessScope.canAccessCreditor(a.getCreditorId()))
                .map(ContactRestrictionService::attemptRow)
                .toList();
        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("debtorId", debtorId);
        dossier.put("generatedAt", now.toString());
        dossier.put("generatedBy", currentActor.actorId());
        dossier.put("restrictions", visibleRestrictions);
        dossier.put("contactAttempts", visibleAttempts);
        byte[] content = json.toJson(dossier).getBytes(StandardCharsets.UTF_8);
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(),
                Map.of("resource", "debtor", "resourceId", debtorId.toString(), "export", "contact_evidence",
                        "restrictions", String.valueOf(visibleRestrictions.size()),
                        "attempts", String.valueOf(visibleAttempts.size()))));
        return new FileContent("evidencias-contato-%s.json".formatted(debtorId), "application/json", content.length,
                new ByteArrayInputStream(content));
    }

    private static Map<String, Object> attemptRow(ContactAttempt a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.getId());
        row.put("creditorId", a.getCreditorId());
        row.put("channel", a.getChannel().name());
        row.put("attemptedAt", a.getAttemptedAt().toString());
        row.put("origin", a.getOrigin().name());
        row.put("referenceType", a.getReferenceType());
        row.put("referenceId", a.getReferenceId());
        return row;
    }

    /** Restrição de um credor exige o credor no escopo; sem credor alcança todos e exige escopo total. */
    private void requireCreditorScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("contact_restriction");
        } else {
            accessScope.requireCreditor(creditorId);
            creditors.get(creditorId);
        }
    }

    private ContactRestriction loadVisible(UUID restrictionId) {
        ContactRestriction restriction = restrictions.findById(restrictionId)
                .orElseThrow(() -> ResourceNotFoundException.of("contact_restriction", restrictionId));
        if (restriction.getCreditorId() != null) {
            accessScope.requireVisible("contact_restriction", restrictionId, restriction.getCreditorId());
        }
        if (restriction.getDebtorId() != null) {
            debtors.get(restriction.getDebtorId());
        } else if (!accessScope.hasAllCreditors()) {
            // restrição de contato sem devedor identificado: só quem vê todos os credores
            throw ResourceNotFoundException.of("contact_restriction", restrictionId);
        }
        return restriction;
    }

    private ContactValueRef contactRef(ContactChannel channel, String contactType, String contactValue) {
        if (contactValue == null || contactValue.isBlank()) {
            Guard.require(contactType == null || contactType.isBlank(), "restriction.contact_required",
                    "Informe o contato a restringir");
            return null;
        }
        ContactValueType type = ComplianceEnums.parseOptional(ContactValueType.class, contactType,
                "restriction.contact_type_invalid", "Tipo do contato");
        if (type == null) {
            type = channel == null ? null : channel.contactValueType().orElse(null);
        }
        Guard.require(type != null, "restriction.contact_type_required", "Informe o tipo do contato (PHONE ou EMAIL)");
        return fingerprint(type, contactValue);
    }

    private ContactValueRef fingerprint(ContactValueType type, String value) {
        ContactFingerprint fingerprint = debtorContacts.fingerprint(type.name(), value);
        return new ContactValueRef(type, fingerprint.hash(), fingerprint.maskedValue());
    }
}
