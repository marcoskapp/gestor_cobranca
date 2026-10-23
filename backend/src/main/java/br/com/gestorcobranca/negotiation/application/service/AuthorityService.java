package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityProvisioning;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.domain.model.authority.AssignmentSubject;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityAssignment;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;
import br.com.gestorcobranca.negotiation.domain.model.authority.StandardAuthorityProfiles;
import br.com.gestorcobranca.negotiation.domain.repository.AuthorityRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class AuthorityService implements AuthorityUseCases, AuthorityProvisioning {

    private static final String PROFILE = "authority_profile";
    private static final String ASSIGNMENT = "authority_assignment";

    private final AuthorityRepository authority;
    private final AuthorityResolver resolver;
    private final UserDirectory users;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final NegotiationMetrics metrics;

    AuthorityService(AuthorityRepository authority, AuthorityResolver resolver, UserDirectory users,
                     CreditorUseCases creditors, AccessScope accessScope, CurrentActor currentActor,
                     AccessLogger accessLogger, AuditTrail auditTrail, NegotiationMetrics metrics) {
        this.authority = authority;
        this.resolver = resolver;
        this.users = users;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfileView> listProfiles() {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        return authority.findAllProfiles().stream().sorted(Comparator.comparing(AuthorityProfile::getCode))
                .map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileView getProfile(UUID profileId) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        return view(loadProfile(profileId));
    }

    @Override
    public ProfileView createProfile(ProfileCommand command) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        String code = command.code() == null ? null : command.code().trim().toUpperCase();
        if (code != null && authority.existsProfileCode(code)) {
            throw new ConflictException("authority.code_taken", "Já existe perfil de alçada com este código");
        }
        requireCreditorsInScope(command.creditorIds());
        AuthorityProfile profile = authority.save(new AuthorityProfile(code, false, parameters(command)));
        auditTrail.record(describe(AuditEntry.of("authority_profile.created", PROFILE, profile.getId()), profile));
        metrics.authorityChanged("PROFILE_CREATED");
        return view(profile);
    }

    @Override
    public ProfileView updateProfile(UUID profileId, ProfileCommand command) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        AuthorityProfile profile = loadProfile(profileId);
        requireCreditorsInScope(profile.getCreditorIds());
        requireCreditorsInScope(command.creditorIds());
        Map<String, Object> before = snapshot(profile);
        profile.define(parameters(command));
        authority.save(profile);
        Map<String, Object> after = snapshot(profile);
        AuditEntry audit = AuditEntry.of("authority_profile.updated", PROFILE, profileId).meta("code", profile.getCode());
        before.forEach((k, v) -> audit.change(k, v, after.get(k)));
        auditTrail.record(audit);
        metrics.authorityChanged("PROFILE_UPDATED");
        return view(profile);
    }

    @Override
    public AssignmentView assign(AssignCommand command) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        AssignmentSubject subject = subject(command.subjectType());
        Guard.require(command.subjectId() != null, "authority.subject_required", "Sujeito obrigatório");
        Guard.require(command.profileId() != null, "authority.profile_required", "Perfil obrigatório");
        if (subject == AssignmentSubject.USER && command.subjectId().equals(currentActor.actorId())) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, currentActor.get(),
                    Map.of("resource", ASSIGNMENT, "reason", "self_assignment")));
            throw new BusinessRuleException("authority.self_assignment", "Ninguém pode atribuir a própria alçada");
        }
        requireAssignmentScope(command.creditorId());
        if (subject == AssignmentSubject.USER) {
            // usuários ainda não ativados podem receber alçada (preparação do onboarding);
            // desativados não autenticam, então a atribuição não tem efeito
            users.find(command.subjectId()).orElseThrow(() -> new ResourceNotFoundException("user", command.subjectId()));
        }
        AuthorityProfile profile = loadProfile(command.profileId());
        Guard.require(command.creditorId() == null ? profile.getCreditorIds().isEmpty()
                        : profile.appliesTo(command.creditorId()), "authority.profile_not_applicable",
                "O perfil não vale para o credor da atribuição");
        AuthorityAssignment assignment = authority.findAssignment(subject, command.subjectId(), command.creditorId())
                .orElse(null);
        String operation;
        AuditEntry audit;
        if (assignment == null) {
            assignment = authority.save(new AuthorityAssignment(subject, command.subjectId(), command.creditorId(),
                    profile.getId()));
            operation = "ASSIGNED";
            audit = AuditEntry.of("authority_assignment.created", ASSIGNMENT, assignment.getId())
                    .created("profile", profile.getCode());
        } else {
            UUID previous = assignment.getProfileId();
            if (!assignment.changeProfile(profile.getId())) {
                return view(assignment);
            }
            authority.save(assignment);
            operation = "REASSIGNED";
            audit = AuditEntry.of("authority_assignment.changed", ASSIGNMENT, assignment.getId())
                    .change("profile", codeOf(previous), profile.getCode());
        }
        auditTrail.record(audit.creditor(command.creditorId()).meta("subjectType", subject)
                .meta("subjectId", command.subjectId()));
        logAccessChange(subject, command.subjectId(), command.creditorId(), profile.getCode(), operation);
        metrics.authorityChanged(operation);
        return view(assignment);
    }

    @Override
    public void unassign(UUID assignmentId) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        AuthorityAssignment assignment = authority.findAssignment(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException(ASSIGNMENT, assignmentId));
        requireAssignmentScope(assignment.getCreditorId());
        String profileCode = codeOf(assignment.getProfileId());
        authority.delete(assignment);
        auditTrail.record(AuditEntry.of("authority_assignment.removed", ASSIGNMENT, assignmentId)
                .creditor(assignment.getCreditorId()).change("profile", profileCode, null)
                .meta("subjectType", assignment.getSubjectType()).meta("subjectId", assignment.getSubjectId()));
        logAccessChange(assignment.getSubjectType(), assignment.getSubjectId(), assignment.getCreditorId(), profileCode,
                "UNASSIGNED");
        metrics.authorityChanged("UNASSIGNED");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentView> assignments(String subjectType, UUID subjectId) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        List<AuthorityAssignment> found = subjectType != null && subjectId != null
                ? authority.findAssignments(subject(subjectType), subjectId) : authority.findAllAssignments();
        return found.stream().filter(a -> a.getCreditorId() == null || accessScope.canAccessCreditor(a.getCreditorId()))
                .map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EffectiveAuthorityView effective(String subjectType, UUID subjectId, UUID creditorId) {
        accessScope.requirePermission(Permission.AUTHORITY_MANAGE);
        Guard.require(subjectId != null && creditorId != null, "authority.subject_required", "Sujeito e credor obrigatórios");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        AssignmentSubject subject = subject(subjectType);
        AuthorityResolver.Resolution r = resolver.resolve(subject, subjectId, creditorId);
        return new EffectiveAuthorityView(subject.name(), subjectId, creditorId, r.source().name(), view(r.profile()));
    }

    @Override
    @Transactional(readOnly = true)
    public EffectiveAuthorityView mine(UUID creditorId) {
        accessScope.requirePermission(Permission.NEGOTIATION_SIMULATE);
        Guard.require(creditorId != null, "authority.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        AuthorityResolver.Resolution r = resolver.resolve(currentActor.get(), creditorId);
        String subject = currentActor.get().isAssistant() ? AssignmentSubject.ASSISTANT_POLICY.name()
                : AssignmentSubject.USER.name();
        return new EffectiveAuthorityView(subject, currentActor.actorId(), creditorId, r.source().name(),
                view(r.profile()));
    }

    @Override
    public int ensureDefaultProfiles() {
        int created = 0;
        for (StandardAuthorityProfiles.Definition definition : StandardAuthorityProfiles.all()) {
            if (authority.existsProfileCode(definition.code())) {
                continue;
            }
            AuthorityProfile profile = authority.save(new AuthorityProfile(definition.code(), true, definition.parameters()));
            auditTrail.record(describe(AuditEntry.of("authority_profile.seeded", PROFILE, profile.getId()), profile));
            created++;
        }
        if (created > 0) {
            metrics.profilesSeeded(created);
        }
        return created;
    }

    private void requireCreditorsInScope(Set<UUID> creditorIds) {
        if (creditorIds == null || creditorIds.isEmpty()) {
            accessScope.requireAllCreditors(PROFILE);
            return;
        }
        creditorIds.forEach(id -> {
            accessScope.requireCreditor(id);
            creditors.get(id);
        });
    }

    private void requireAssignmentScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors(ASSIGNMENT);
        } else {
            accessScope.requireCreditor(creditorId);
            creditors.get(creditorId);
        }
    }

    private void logAccessChange(AssignmentSubject subject, UUID subjectId, UUID creditorId, String profileCode,
                                 String operation) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("change", "negotiation_authority");
        details.put("operation", operation);
        details.put("subjectType", subject.name());
        details.put("subjectId", subjectId.toString());
        details.put("creditorId", creditorId == null ? "ALL" : creditorId.toString());
        details.put("profile", profileCode);
        accessLogger.log(AccessEvent.of(AccessEventType.PERMISSION_CHANGED, currentActor.get(), details));
    }

    private String codeOf(UUID profileId) {
        return authority.findProfile(profileId).map(AuthorityProfile::getCode).orElse(String.valueOf(profileId));
    }

    private AuthorityProfile loadProfile(UUID profileId) {
        return authority.findProfile(profileId).orElseThrow(() -> new ResourceNotFoundException(PROFILE, profileId));
    }

    private static AssignmentSubject subject(String value) {
        try {
            return AssignmentSubject.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("authority.subject_type_invalid", "Tipo de sujeito inválido: " + value);
        }
    }

    private static AuthorityProfile.Parameters parameters(ProfileCommand c) {
        return new AuthorityProfile.Parameters(c.name(), c.description(), c.maxConcessionLevel(), c.maxDiscountShare(),
                c.maxInstallments(), c.maxAgreementAmount(), c.minDownPaymentShare(), c.canFormalize(), c.canApprove(),
                c.maxRenegotiations(), c.creditorIds());
    }

    private static AuditEntry describe(AuditEntry audit, AuthorityProfile p) {
        snapshot(p).forEach(audit::created);
        return audit;
    }

    private static Map<String, Object> snapshot(AuthorityProfile p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", p.getCode());
        values.put("name", p.getName());
        values.put("maxConcessionLevel", p.getMaxConcessionLevel());
        values.put("maxDiscountShare", p.getMaxDiscountShare());
        values.put("maxInstallments", p.getMaxInstallments());
        values.put("maxAgreementAmount", p.getMaxAgreementAmount());
        values.put("minDownPaymentShare", p.getMinDownPaymentShare());
        values.put("canFormalize", p.isCanFormalize());
        values.put("canApprove", p.isCanApprove());
        values.put("maxRenegotiations", p.getMaxRenegotiations());
        values.put("creditorIds", p.getCreditorIds().stream().sorted().toList().toString());
        return values;
    }

    private ProfileView view(AuthorityProfile p) {
        return new ProfileView(p.getId(), p.getCode(), p.getName(), p.getDescription(), p.isSystemProfile(),
                p.getMaxConcessionLevel(), p.getMaxDiscountShare().value(), p.getMaxInstallments(),
                p.getMaxAgreementAmount() == null ? null : p.getMaxAgreementAmount().amount(),
                p.getMinDownPaymentShare().value(), p.isCanFormalize(), p.isCanApprove(), p.getMaxRenegotiations(),
                p.getCreditorIds(), authority.countAssignmentsOf(p.getId()));
    }

    private AssignmentView view(AuthorityAssignment a) {
        return new AssignmentView(a.getId(), a.getSubjectType().name(), a.getSubjectId(), a.getCreditorId(),
                a.getProfileId(), codeOf(a.getProfileId()));
    }
}
