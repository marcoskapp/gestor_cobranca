package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.domain.model.ApprovalStatus;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.MessageTemplate;
import br.com.gestorcobranca.communication.domain.model.ProviderCategory;
import br.com.gestorcobranca.communication.domain.model.TemplatePurpose;
import br.com.gestorcobranca.communication.domain.model.TemplateStatus;
import br.com.gestorcobranca.communication.domain.model.TemplateVariable;
import br.com.gestorcobranca.communication.domain.model.VariableType;
import br.com.gestorcobranca.communication.domain.repository.MessageTemplateRepository;
import br.com.gestorcobranca.communication.domain.service.ProviderTemplateRules;
import br.com.gestorcobranca.communication.domain.service.TemplateRenderer;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class TemplateService implements TemplateUseCases {

    private final MessageTemplateRepository templates;
    private final TemplateVariables variables;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    TemplateService(MessageTemplateRepository templates, TemplateVariables variables, CreditorUseCases creditors,
                    AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                    BusinessEventLogger businessEvents, Clock clock) {
        this.templates = templates;
        this.variables = variables;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    public TemplateView createDraft(CreateTemplateCommand command) {
        Guard.require(command != null, "template.required", "Dados do template obrigatórios");
        requireScope(command.creditorId());
        if (command.creditorId() != null) {
            creditors.get(command.creditorId());
        }
        String code = command.code() == null ? null : command.code().trim().toUpperCase(Locale.ROOT);
        Guard.require(code != null && MessageTemplate.CODE.matcher(code).matches(), "template.code_invalid",
                "Código em maiúsculas, números e _ (2 a 60)");
        ChannelType channel = CommunicationEnums.parse(ChannelType.class, command.channel(), "template.channel_invalid",
                "Canal");
        templates.findPublished(command.creditorId(), code).ifPresent(published ->
                Guard.require(published.getChannel() == channel, "template.channel_mismatch",
                        "O código %s pertence ao canal %s".formatted(code, published.getChannel())));
        int next = templates.maxVersion(command.creditorId(), code) + 1;
        MessageTemplate template = templates.save(new MessageTemplate(command.creditorId(), code, next, channel,
                content(command.content(), channel)));
        auditTrail.record(AuditEntry.of("message_template.created", "message_template", template.getId())
                .creditor(template.getCreditorId())
                .created("code", code)
                .created("templateVersion", next)
                .created("channel", channel)
                .created("providerCategory", template.getProviderCategory())
                .created("body", template.getBody())
                .created("variables", template.getVariables()));
        return view(template);
    }

    @Override
    public TemplateView updateDraft(UUID templateId, TemplateContent content) {
        MessageTemplate template = forCommand(templateId);
        String beforeBody = template.getBody();
        String beforeSubject = template.getSubject();
        String beforeVariables = template.getVariables();
        ApprovalStatus beforeApproval = template.getApprovalStatus();
        ProviderCategory beforeCategory = template.getProviderCategory();
        template.edit(content(content, template.getChannel()));
        templates.save(template);
        auditTrail.record(AuditEntry.of("message_template.updated", "message_template", template.getId())
                .creditor(template.getCreditorId())
                .change("subject", beforeSubject, template.getSubject())
                .change("body", beforeBody, template.getBody())
                .change("variables", beforeVariables, template.getVariables())
                .change("providerCategory", beforeCategory, template.getProviderCategory())
                .change("approvalStatus", beforeApproval, template.getApprovalStatus()));
        return view(template);
    }

    @Override
    public TemplateView registerApproval(UUID templateId, ApprovalCommand command) {
        Guard.require(command != null, "template.approval_required", "Decisão obrigatória");
        MessageTemplate template = forCommand(templateId);
        ApprovalStatus decision = CommunicationEnums.parse(ApprovalStatus.class, command.decision(),
                "template.approval_invalid", "Decisão");
        Instant now = Instant.now(clock);
        Instant decidedAt = command.decidedAt() == null ? now : command.decidedAt();
        Guard.require(!decidedAt.isAfter(now.plusSeconds(300)), "template.approval_in_future", "Data da decisão no futuro");
        ApprovalStatus before = template.getApprovalStatus();
        template.registerApproval(decision, command.providerTemplateName(), command.reference(), command.note(),
                decidedAt, currentActor.actorId());
        templates.save(template);
        auditTrail.record(AuditEntry.of("message_template.approval_registered", "message_template", template.getId())
                .creditor(template.getCreditorId())
                .change("approvalStatus", before, decision)
                .meta("providerTemplateName", template.getProviderTemplateName())
                .meta("reference", template.getApprovalReference())
                .meta("decidedAt", template.getApprovalDecidedAt()));
        return view(template);
    }

    @Override
    public TemplateView publish(UUID templateId) {
        MessageTemplate template = forCommand(templateId);
        Instant now = Instant.now(clock);
        Integer retiredVersion = null;
        var current = templates.findPublished(template.getCreditorId(), template.getCode());
        if (current.isPresent() && !current.get().getId().equals(template.getId())) {
            Guard.require(current.get().getChannel() == template.getChannel(), "template.channel_mismatch",
                    "A versão publicada do código é de outro canal");
            current.get().retire(now);
            // retirar antes de publicar: o índice único de versão publicada é verificado a cada linha
            templates.saveAndFlush(current.get());
            retiredVersion = current.get().getTemplateVersion();
        }
        template.publish(now, currentActor.actorId());
        templates.saveAndFlush(template);
        auditTrail.record(AuditEntry.of("message_template.published", "message_template", template.getId())
                .creditor(template.getCreditorId())
                .change("status", TemplateStatus.DRAFT, TemplateStatus.PUBLISHED)
                .meta("code", template.getCode())
                .meta("templateVersion", template.getTemplateVersion())
                .meta("retiredVersion", retiredVersion));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.TEMPLATE_PUBLISHED)
                .creditor(template.getCreditorId())
                .reference("message_template", template.getId())
                .channel(template.getChannel().name())
                .attr("code", template.getCode())
                .attr("templateVersion", template.getTemplateVersion())
                .attr("purpose", template.getPurpose())
                .attr("variables", variables.parse(template.getVariables()).size())
                .attr("replacedVersion", retiredVersion));
        return view(template);
    }

    @Override
    public TemplateView retire(UUID templateId) {
        MessageTemplate template = forCommand(templateId);
        TemplateStatus before = template.getStatus();
        template.retire(Instant.now(clock));
        templates.save(template);
        auditTrail.record(AuditEntry.of("message_template.retired", "message_template", template.getId())
                .creditor(template.getCreditorId())
                .change("status", before, TemplateStatus.RETIRED));
        return view(template);
    }

    @Override
    @Transactional(readOnly = true)
    public TemplateView get(UUID templateId) {
        return view(visible(templateId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TemplateView> search(TemplateFilter filter, PageQuery page) {
        TemplateFilter f = filter == null ? new TemplateFilter(null, null, null, null) : filter;
        if (f.creditorId() != null) {
            accessScope.requireVisible("creditor", f.creditorId(), f.creditorId());
        }
        String channel = CommunicationEnums.name(CommunicationEnums.parseOptional(ChannelType.class, f.channel(),
                "template.channel_invalid", "Canal"));
        String status = CommunicationEnums.name(CommunicationEnums.parseOptional(TemplateStatus.class, f.status(),
                "template.status_invalid", "Situação"));
        String code = f.code() == null || f.code().isBlank() ? null : f.code().trim().toUpperCase(Locale.ROOT);
        var allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        return PageResponse.of(templates.search(allowed, true, f.creditorId(), code, channel, status,
                page.toPageable(Sort.by("code", "templateVersion").ascending())), this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateView> findEffective(UUID creditorId, String code) {
        Guard.require(code != null && !code.isBlank(), "template.code_invalid", "Código do template obrigatório");
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return templates.findPublished(creditorId, normalized)
                .or(() -> creditorId == null ? Optional.empty() : templates.findPublished(null, normalized))
                .map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(UUID templateId, Map<String, String> values) {
        MessageTemplate template = visible(templateId);
        TemplateRenderer.Rendered rendered = TemplateRenderer.render(template.getSubject(), template.getBody(),
                variables.parse(template.getVariables()), values);
        return new Preview(rendered.subject(), rendered.body(), rendered.parameters());
    }

    private MessageTemplate.Content content(TemplateContent c, ChannelType channel) {
        Guard.require(c != null, "template.content_required", "Conteúdo obrigatório");
        List<TemplateVariable> declared = c.variables() == null ? List.of() : c.variables().stream()
                .map(v -> new TemplateVariable(v.name(), CommunicationEnums.parse(VariableType.class, v.type(),
                        "template.variable_type_invalid", "Tipo da variável"), v.required(), v.maxLength()))
                .toList();
        TemplateRenderer.validateDefinition(c.subject(), c.body(), declared);
        ProviderCategory category = CommunicationEnums.parseOptional(ProviderCategory.class, c.providerCategory(),
                "template.category_invalid", "Categoria no provedor");
        if (category == null && channel == ChannelType.WHATSAPP) {
            category = ProviderCategory.UTILITY;
        }
        ProviderTemplateRules.validate(channel, category, declared);
        TemplatePurpose purpose = CommunicationEnums.parse(TemplatePurpose.class, c.purpose(), "template.purpose_invalid",
                "Finalidade");
        String providerName = c.providerTemplateName() == null || c.providerTemplateName().isBlank() ? null
                : c.providerTemplateName().trim().toLowerCase(Locale.ROOT);
        return new MessageTemplate.Content(c.name(), purpose, c.subject(), c.body(), variables.toJson(declared),
                c.language(), providerName, category);
    }

    private MessageTemplate visible(UUID templateId) {
        MessageTemplate template = templates.findById(templateId)
                .orElseThrow(() -> ResourceNotFoundException.of("message_template", templateId));
        if (template.getCreditorId() != null) {
            accessScope.requireVisible("message_template", templateId, template.getCreditorId());
        }
        return template;
    }

    private MessageTemplate forCommand(UUID templateId) {
        MessageTemplate template = visible(templateId);
        requireScope(template.getCreditorId());
        return template;
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors("message_template");
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    TemplateView view(MessageTemplate t) {
        List<VariableInput> vars = variables.parse(t.getVariables()).stream()
                .map(v -> new VariableInput(v.name(), v.type().name(), v.required(), v.maxLength()))
                .toList();
        return new TemplateView(t.getId(), t.getCreditorId(), t.getCode(), t.getTemplateVersion(), t.getChannel().name(),
                t.getName(), t.getPurpose().name(), t.getSubject(), t.getBody(), vars, t.getLanguage(), t.getStatus().name(),
                t.getProviderTemplateName(), CommunicationEnums.name(t.getProviderCategory()), t.getApprovalStatus().name(), t.getApprovalReference(), t.getApprovalNote(),
                t.getApprovalDecidedAt(), t.getApprovalRegisteredBy(), t.getPublishedAt(), t.getPublishedBy(),
                t.getRetiredAt(), t.getCreatedAt(), t.getVersion() == null ? 0 : t.getVersion());
    }
}
