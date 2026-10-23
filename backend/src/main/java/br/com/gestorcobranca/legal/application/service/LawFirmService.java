package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases;
import br.com.gestorcobranca.legal.domain.model.LawFirm;
import br.com.gestorcobranca.legal.domain.model.LawFirmMember;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.legal.domain.repository.LawFirmRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
class LawFirmService implements LawFirmUseCases {

    static final String ENTITY = "law_firm";

    private final LawFirmRepository firms;
    private final LawFirmMemberRepository members;
    private final UserDirectory users;
    private final LegalAccess access;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;
    private final Clock clock;

    LawFirmService(LawFirmRepository firms, LawFirmMemberRepository members, UserDirectory users, LegalAccess access,
                   AuditTrail auditTrail, AccessLogger accessLogger, CurrentActor currentActor, Clock clock) {
        this.firms = firms;
        this.members = members;
        this.users = users;
        this.access = access;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LawFirmView> list() {
        access.requireInternal(ENTITY);
        return firms.findAllByOrderByNameAsc().stream().map(LegalViews::firm).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LawFirmView get(UUID lawFirmId) {
        access.requireInternal(ENTITY);
        return LegalViews.firm(load(lawFirmId));
    }

    @Override
    @Transactional(readOnly = true)
    public LegalProfileView me() {
        LegalAccess.ActorScope scope = access.scope();
        if (!scope.external()) {
            return new LegalProfileView(false, null, null, true);
        }
        String name = firms.findById(scope.lawFirmId()).map(LawFirm::getName).orElse(null);
        return new LegalProfileView(true, scope.lawFirmId(), name, scope.firmActive());
    }

    @Override
    public LawFirmView create(LawFirmCommand c) {
        manage();
        LawFirm firm = new LawFirm(c.name(), c.cnpj(), c.oabRegistration(), c.contactEmail());
        if (firms.existsByCnpj(firm.getCnpj())) {
            throw new ConflictException("law_firm.duplicate", "Escritório já cadastrado com este CNPJ");
        }
        firms.save(firm);
        auditTrail.record(AuditEntry.of("law_firm.created", ENTITY, firm.getId())
                .created("name", firm.getName())
                .created("cnpj", new Document(Document.DocumentType.CNPJ, firm.getCnpj()).masked())
                .created("oabRegistration", firm.getOabRegistration()));
        return LegalViews.firm(firm);
    }

    @Override
    public LawFirmView update(UUID lawFirmId, LawFirmCommand c) {
        manage();
        LawFirm firm = load(lawFirmId);
        String name = firm.getName();
        String oab = firm.getOabRegistration();
        String email = firm.getContactEmail();
        if (firm.describe(c.name(), c.oabRegistration(), c.contactEmail())) {
            firms.save(firm);
            auditTrail.record(AuditEntry.of("law_firm.updated", ENTITY, firm.getId())
                    .change("name", name, firm.getName()).change("oabRegistration", oab, firm.getOabRegistration())
                    .change("contactEmail", email == null ? null : "***", firm.getContactEmail() == null ? null : "***"));
        }
        return LegalViews.firm(firm);
    }

    @Override
    public LawFirmView setActive(UUID lawFirmId, boolean active) {
        manage();
        LawFirm firm = load(lawFirmId);
        if (firm.setActive(active)) {
            firms.save(firm);
            auditTrail.record(AuditEntry.of(active ? "law_firm.activated" : "law_firm.deactivated", ENTITY, firm.getId())
                    .change("active", !active, active));
            accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_CHANGED, currentActor.get(),
                    Map.of("resource", ENTITY, "lawFirmId", firm.getId().toString(), "active", String.valueOf(active))));
        }
        return LegalViews.firm(firm);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberView> members(UUID lawFirmId) {
        access.requireInternal(ENTITY);
        LawFirm firm = load(lawFirmId);
        List<LawFirmMember> list = members.findByLawFirmIdOrderByJoinedAtAsc(firm.getId());
        Map<UUID, String> names = users.findAll(list.stream().map(LawFirmMember::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(UserSummary::id, UserSummary::name, (a, b) -> a));
        return list.stream().map(mb -> view(mb, names.get(mb.getUserId()))).toList();
    }

    @Override
    public MemberView addMember(UUID lawFirmId, UUID userId) {
        manage();
        LawFirm firm = load(lawFirmId);
        if (!firm.isActive()) {
            throw new ConflictException("law_firm.inactive", "Escritório inativo");
        }
        UserSummary user = users.find(userId).filter(UserSummary::active)
                .orElseThrow(() -> new BusinessRuleException("law_firm.user_invalid", "Usuário inexistente ou inativo"));
        if (members.findByUserIdAndActiveTrue(userId).isPresent()) {
            throw new ConflictException("law_firm.member_duplicate", "Usuário já vinculado a um escritório");
        }
        LawFirmMember member = members.save(new LawFirmMember(firm.getId(), userId, Instant.now(clock)));
        auditTrail.record(AuditEntry.of("law_firm.member_added", ENTITY, firm.getId()).meta("userId", userId));
        accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_CHANGED, currentActor.get(),
                Map.of("resource", ENTITY, "lawFirmId", firm.getId().toString(), "userId", userId.toString(),
                        "operation", "member_added")));
        return view(member, user.name());
    }

    @Override
    public MemberView removeMember(UUID lawFirmId, UUID userId) {
        manage();
        LawFirm firm = load(lawFirmId);
        LawFirmMember member = members.findByLawFirmIdAndUserIdAndActiveTrue(firm.getId(), userId)
                .orElseThrow(() -> ResourceNotFoundException.of("law_firm_member", userId));
        member.leave(Instant.now(clock));
        members.save(member);
        auditTrail.record(AuditEntry.of("law_firm.member_removed", ENTITY, firm.getId()).meta("userId", userId));
        accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_CHANGED, currentActor.get(),
                Map.of("resource", ENTITY, "lawFirmId", firm.getId().toString(), "userId", userId.toString(),
                        "operation", "member_removed")));
        return view(member, users.find(userId).map(UserSummary::name).orElse(null));
    }

    private void manage() {
        access.requireInternal(ENTITY);
        access.requireAllCreditors(ENTITY);
    }

    private static MemberView view(LawFirmMember m, String name) {
        return new MemberView(m.getLawFirmId(), m.getUserId(), name, m.isActive(), m.getJoinedAt(), m.getLeftAt());
    }

    private LawFirm load(UUID id) {
        return firms.findById(id).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, id));
    }
}
