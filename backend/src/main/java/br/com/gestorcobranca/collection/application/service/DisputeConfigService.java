package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases;
import br.com.gestorcobranca.collection.domain.model.DisputeDeadlineRule;
import br.com.gestorcobranca.collection.domain.model.DisputeDistributionMode;
import br.com.gestorcobranca.collection.domain.model.DisputePoolMember;
import br.com.gestorcobranca.collection.domain.model.DisputeSettings;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplate;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.collection.domain.repository.DisputeDeadlineRuleRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputePoolMemberRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeSettingsRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeTemplateRepository;
import br.com.gestorcobranca.collection.domain.service.DisputeDeadlines;
import br.com.gestorcobranca.collection.domain.service.DisputeTemplates;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class DisputeConfigService implements DisputeConfigUseCases {

    private final DisputeSettingsRepository settings;
    private final DisputeDeadlineRuleRepository rules;
    private final DisputePoolMemberRepository pool;
    private final DisputeTemplateRepository templates;
    private final DisputeRepository disputes;
    private final SettingsProvider operationSettings;
    private final CreditorUseCases creditors;
    private final UserDirectory users;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final Clock clock;

    DisputeConfigService(DisputeSettingsRepository settings, DisputeDeadlineRuleRepository rules,
                         DisputePoolMemberRepository pool, DisputeTemplateRepository templates,
                         DisputeRepository disputes, SettingsProvider operationSettings, CreditorUseCases creditors,
                         UserDirectory users, AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                         Clock clock) {
        this.settings = settings;
        this.rules = rules;
        this.pool = pool;
        this.templates = templates;
        this.disputes = disputes;
        this.operationSettings = operationSettings;
        this.creditors = creditors;
        this.users = users;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ parâmetros

    @Override
    @Transactional(readOnly = true)
    public DisputeSettingsView settings() {
        return view(settings.findFirstByOrderByCreatedAtAsc().orElse(null));
    }

    @Override
    public DisputeSettingsView updateSettings(DisputeSettingsCommand command) {
        requireManageAll("dispute_settings");
        Guard.require(command != null, "dispute_settings.required", "Parâmetros obrigatórios");
        DisputeSettings.Values values = new DisputeSettings.Values(
                CollectionEnums.parse(DisputeDistributionMode.class, command.distributionMode(),
                        "dispute_settings.mode_invalid", "Modo de distribuição"),
                command.internalDays(), command.creditorRequestDays(), command.reminderDaysBefore(),
                command.evidenceLinkDays());
        DisputeSettings current = settings.findFirstByOrderByCreatedAtAsc().orElse(null);
        DisputeSettings.Values before = current == null ? DisputeSettings.Values.DEFAULTS : current.values();
        DisputeSettings saved;
        if (current == null) {
            saved = settings.save(new DisputeSettings(values));
        } else {
            current.define(values);
            saved = settings.save(current);
        }
        auditTrail.record(AuditEntry.of("dispute_settings.updated", "dispute_settings", saved.getId())
                .change("distributionMode", before.distributionMode(), values.distributionMode())
                .change("internalDays", before.internalDays(), values.internalDays())
                .change("creditorRequestDays", before.creditorRequestDays(), values.creditorRequestDays())
                .change("reminderDaysBefore", before.reminderDaysBefore(), values.reminderDaysBefore())
                .change("evidenceLinkDays", before.evidenceLinkDays(), values.evidenceLinkDays()));
        return view(saved);
    }

    // ------------------------------------------------------------------ prazos

    @Override
    @Transactional(readOnly = true)
    public List<DeadlineRuleView> rules() {
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        return rules.findByActiveTrue().stream()
                .filter(r -> r.getCreditorId() == null || allowed == null || allowed.contains(r.getCreditorId()))
                .sorted(Comparator.comparing((DisputeDeadlineRule r) -> r.getCreditorId() == null ? "" : r.getCreditorId().toString())
                        .thenComparing(r -> r.getType() == null ? "" : r.getType().name()))
                .map(DisputeConfigService::view).toList();
    }

    @Override
    public DeadlineRuleView createRule(DeadlineRuleCommand command) {
        Guard.require(command != null, "dispute_rule.required", "Dados da regra obrigatórios");
        requireScope(command.creditorId(), "dispute_deadline_rule");
        DisputeType type = CollectionEnums.parseOptional(DisputeType.class, command.type(), "dispute_rule.type_invalid",
                "Tipo");
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        boolean exists = rules.findByActiveTrue().stream().anyMatch(r -> Objects.equals(r.getCreditorId(), command.creditorId())
                && r.getType() == type);
        if (exists) {
            throw new ConflictException("dispute_rule.duplicated", "Já existe regra para este tipo e credor");
        }
        DisputeDeadlineRule rule = rules.save(new DisputeDeadlineRule(command.creditorId(), type, command.responseDays(),
                command.internalDays()));
        auditTrail.record(AuditEntry.of("dispute_rule.created", "dispute_deadline_rule", rule.getId())
                .creditor(rule.getCreditorId())
                .created("type", rule.getType())
                .created("responseDays", rule.getResponseDays())
                .created("internalDays", rule.getInternalDays()));
        return view(rule);
    }

    @Override
    public DeadlineRuleView updateRule(UUID ruleId, int responseDays, int internalDays) {
        DisputeDeadlineRule rule = loadRule(ruleId);
        int beforeResponse = rule.getResponseDays();
        int beforeInternal = rule.getInternalDays();
        rule.define(responseDays, internalDays);
        DisputeDeadlineRule saved = rules.save(rule);
        auditTrail.record(AuditEntry.of("dispute_rule.updated", "dispute_deadline_rule", ruleId)
                .creditor(saved.getCreditorId())
                .change("responseDays", beforeResponse, saved.getResponseDays())
                .change("internalDays", beforeInternal, saved.getInternalDays()));
        return view(saved);
    }

    @Override
    public void removeRule(UUID ruleId) {
        DisputeDeadlineRule rule = loadRule(ruleId);
        rule.deactivate();
        rules.save(rule);
        auditTrail.record(AuditEntry.of("dispute_rule.removed", "dispute_deadline_rule", ruleId)
                .creditor(rule.getCreditorId()).change("active", true, false));
    }

    @Override
    @Transactional(readOnly = true)
    public DeadlinePreview previewDeadlines(UUID creditorId, String type) {
        Guard.require(creditorId != null, "dispute_rule.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        DisputeType parsed = CollectionEnums.parse(DisputeType.class, type, "dispute_rule.type_invalid", "Tipo");
        DisputeSettings.Values values = settings.findFirstByOrderByCreatedAtAsc().map(DisputeSettings::values)
                .orElse(DisputeSettings.Values.DEFAULTS);
        LocalDate today = CollectionViews.today(clock);
        DisputeDeadlines.Deadlines d = DisputeDeadlines.resolve(today, creditorId, parsed, null, rules.findByActiveTrue(),
                operationSettings.current().disputeResponseDays(), values.internalDays());
        return new DeadlinePreview(d.responseDue(), d.internalDue(), d.responseDays(), d.internalDays(),
                d.source().name());
    }

    // ------------------------------------------------------------------ distribuição

    @Override
    @Transactional(readOnly = true)
    public List<PoolMemberView> pool() {
        List<DisputePoolMember> members = pool.findByActiveTrue();
        Map<UUID, UserSummary> byId = users.findAll(members.stream().map(DisputePoolMember::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UserSummary::id, Function.identity(), (a, b) -> a));
        Map<UUID, Long> counts = disputes.countOpenByAssignees(byId.keySet());
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        List<PoolMemberView> out = new ArrayList<>();
        for (DisputePoolMember m : members) {
            if (m.getCreditorId() != null && allowed != null && !allowed.contains(m.getCreditorId())) {
                continue;
            }
            UserSummary u = byId.get(m.getUserId());
            boolean eligible = u != null && (m.getCreditorId() == null
                    ? u.active() && (u.permissions().contains(Permission.DISPUTE_WORK.name())
                    || u.permissions().contains(Permission.DISPUTE_MANAGE.name()))
                    : DisputeAssignments.isEligible(u, m.getCreditorId()));
            out.add(new PoolMemberView(m.getId(), m.getCreditorId(), m.getUserId(), u == null ? null : u.name(), eligible,
                    counts.getOrDefault(m.getUserId(), 0L)));
        }
        out.sort(Comparator.comparing((PoolMemberView v) -> v.creditorId() == null ? "" : v.creditorId().toString())
                .thenComparing(v -> v.userName() == null ? "" : v.userName()));
        return out;
    }

    @Override
    public PoolMemberView addPoolMember(UUID creditorId, UUID userId) {
        requireScope(creditorId, "dispute_pool_member");
        Guard.require(userId != null, "dispute_pool.user_required", "Analista obrigatório");
        UserSummary user = users.find(userId).orElseThrow(() -> new br.com.gestorcobranca.shared.domain.BusinessRuleException(
                "dispute_pool.user_invalid", "Analista inexistente"));
        boolean works = user.active() && (user.permissions().contains(Permission.DISPUTE_WORK.name())
                || user.permissions().contains(Permission.DISPUTE_MANAGE.name()));
        Guard.require(works && (creditorId == null || user.canAccessCreditor(creditorId)), "dispute_pool.user_invalid",
                "O analista precisa estar ativo, trabalhar contestações e ter acesso ao credor");
        boolean exists = pool.findByActiveTrue().stream()
                .anyMatch(m -> m.getUserId().equals(userId) && Objects.equals(m.getCreditorId(), creditorId));
        if (exists) {
            throw new ConflictException("dispute_pool.duplicated", "O analista já está neste grupo");
        }
        DisputePoolMember member = pool.save(new DisputePoolMember(creditorId, userId));
        auditTrail.record(AuditEntry.of("dispute_pool.member_added", "dispute_pool_member", member.getId())
                .creditor(creditorId).created("userId", userId));
        return new PoolMemberView(member.getId(), creditorId, userId, user.name(), true,
                disputes.countOpenByAssignees(List.of(userId)).getOrDefault(userId, 0L));
    }

    @Override
    public void removePoolMember(UUID memberId) {
        DisputePoolMember member = pool.findById(memberId).filter(DisputePoolMember::isActive)
                .orElseThrow(() -> ResourceNotFoundException.of("dispute_pool_member", memberId));
        requireScope(member.getCreditorId(), "dispute_pool_member");
        member.deactivate();
        pool.save(member);
        auditTrail.record(AuditEntry.of("dispute_pool.member_removed", "dispute_pool_member", memberId)
                .creditor(member.getCreditorId()).meta("userId", member.getUserId()));
    }

    // ------------------------------------------------------------------ modelos

    @Override
    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        Map<String, DisputeTemplate> latest = templates.findAll().stream()
                .collect(Collectors.toMap(t -> t.getKind() + ":" + t.getTemplateKey(), Function.identity(),
                        (a, b) -> a.getTemplateVersion() >= b.getTemplateVersion() ? a : b));
        List<TemplateView> out = new ArrayList<>();
        for (DisputeTemplateKind kind : DisputeTemplateKind.values()) {
            for (String key : DisputeTemplates.keys(kind)) {
                DisputeTemplate t = latest.get(kind + ":" + key);
                out.add(t == null ? defaultView(kind, key) : view(t));
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TemplateView> templateHistory(String kind, String key) {
        DisputeTemplateKind parsed = kind(kind);
        String k = key(parsed, key);
        return templates.findByKindAndTemplateKeyOrderByTemplateVersionDesc(parsed, k).stream()
                .map(DisputeConfigService::view).toList();
    }

    @Override
    public TemplateView saveTemplate(String kind, String key, String subject, String body) {
        requireManageAll("dispute_template");
        DisputeTemplateKind parsed = kind(kind);
        String k = key(parsed, key);
        DisputeTemplates.validate(parsed, k, subject, body);
        return newVersion(parsed, k, subject, body, "dispute_template.saved");
    }

    @Override
    public TemplateView restoreDefaultTemplate(String kind, String key) {
        requireManageAll("dispute_template");
        DisputeTemplateKind parsed = kind(kind);
        String k = key(parsed, key);
        DisputeTemplates.Text text = DisputeTemplates.defaultFor(parsed, k);
        return newVersion(parsed, k, text.subject(), text.body(), "dispute_template.restored");
    }

    private TemplateView newVersion(DisputeTemplateKind kind, String key, String subject, String body, String action) {
        int next = templates.findFirstByKindAndTemplateKeyOrderByTemplateVersionDesc(kind, key)
                .map(t -> t.getTemplateVersion() + 1).orElse(1);
        DisputeTemplate saved = templates.save(new DisputeTemplate(kind, key, next, subject, body));
        auditTrail.record(AuditEntry.of(action, "dispute_template", saved.getId())
                .created("kind", kind)
                .created("key", key)
                .created("version", next)
                .created("subject", saved.getSubject()));
        return view(saved);
    }

    // ------------------------------------------------------------------ apoio

    private void requireManageAll(String resource) {
        Actor actor = currentActor.get();
        if (actor.isUser()) {
            accessScope.requirePermission(Permission.DISPUTE_MANAGE);
        }
        accessScope.requireAllCreditors(resource);
    }

    private void requireScope(UUID creditorId, String resource) {
        Actor actor = currentActor.get();
        if (actor.isUser()) {
            accessScope.requirePermission(Permission.DISPUTE_MANAGE);
        }
        if (creditorId == null) {
            accessScope.requireAllCreditors(resource);
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private DisputeDeadlineRule loadRule(UUID ruleId) {
        DisputeDeadlineRule rule = rules.findById(ruleId).filter(DisputeDeadlineRule::isActive)
                .orElseThrow(() -> ResourceNotFoundException.of("dispute_deadline_rule", ruleId));
        requireScope(rule.getCreditorId(), "dispute_deadline_rule");
        return rule;
    }

    private static DisputeTemplateKind kind(String kind) {
        return CollectionEnums.parse(DisputeTemplateKind.class, kind, "dispute_template.kind_invalid", "Tipo de modelo");
    }

    private static String key(DisputeTemplateKind kind, String key) {
        String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        Guard.require(DisputeTemplates.keys(kind).contains(k), "dispute_template.key_invalid", "Modelo inexistente: " + key);
        return k;
    }

    private DisputeSettingsView view(DisputeSettings s) {
        DisputeSettings.Values v = s == null ? DisputeSettings.Values.DEFAULTS : s.values();
        return new DisputeSettingsView(v.distributionMode().name(), v.internalDays(), v.creditorRequestDays(),
                v.reminderDaysBefore(), v.evidenceLinkDays(), operationSettings.current().disputeResponseDays(),
                s != null, s == null ? null : s.getUpdatedAt());
    }

    private static DeadlineRuleView view(DisputeDeadlineRule r) {
        return new DeadlineRuleView(r.getId(), r.getCreditorId(), CollectionEnums.name(r.getType()), r.getResponseDays(),
                r.getInternalDays(), r.source().name(), r.getUpdatedAt(), r.getVersion() == null ? 0 : r.getVersion());
    }

    private static TemplateView view(DisputeTemplate t) {
        return new TemplateView(t.getKind().name(), t.getTemplateKey(), t.getTemplateVersion(), t.getSubject(), t.getBody(),
                true, DisputeTemplates.variables(t.getKind()), t.getCreatedAt(), t.getCreatedBy());
    }

    private static TemplateView defaultView(DisputeTemplateKind kind, String key) {
        DisputeTemplates.Text text = DisputeTemplates.defaultFor(kind, key);
        return new TemplateView(kind.name(), key, 0, text.subject(), text.body(), false, DisputeTemplates.variables(kind),
                null, null);
    }
}
