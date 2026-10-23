package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateFilter;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateView;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Validação, na publicação da régua (e no início de um teste A/B), de que cada etapa de mensagem usa
 * um template publicado no communication para o credor (ou da assessoria) no canal da etapa, e de que
 * as variáveis da etapa cobrem exatamente as declaradas no template — o erro aparece para quem
 * configura, não para o devedor no meio da régua.
 */
@Component
class TemplateCheck {

    private final TemplateUseCases templates;

    TemplateCheck(TemplateUseCases templates) {
        this.templates = templates;
    }

    void check(String stepCode, StepAction action, UUID creditorId) {
        if (!action.sendsMessage()) {
            return;
        }
        TemplateView template = templates.search(new TemplateFilter(null, action.templateCode(), action.channel(),
                        "PUBLISHED"), PageQuery.of(0, PageQuery.MAX_SIZE)).content().stream()
                .filter(t -> t.creditorId() == null || t.creditorId().equals(creditorId))
                // o do credor prevalece sobre o da assessoria (mesma regra do envio)
                .max(Comparator.comparing(t -> t.creditorId() != null))
                .orElseThrow(() -> new BusinessRuleException("cadence.template_not_published",
                        "Etapa %s: template %s não publicado no canal %s".formatted(stepCode, action.templateCode(),
                                action.channel())));
        Set<String> declared = template.variables().stream().map(VariableInput::name).collect(Collectors.toSet());
        Set<String> required = template.variables().stream().filter(VariableInput::required).map(VariableInput::name)
                .collect(Collectors.toSet());
        Set<String> mapped = action.variables().keySet();
        Guard.require(declared.containsAll(mapped), "cadence.template_variable_unknown",
                "Etapa %s: variáveis fora do template: %s".formatted(stepCode, mapped.stream()
                        .filter(v -> !declared.contains(v)).sorted().collect(Collectors.joining(", "))));
        Guard.require(mapped.containsAll(required), "cadence.template_variable_missing",
                "Etapa %s: variáveis obrigatórias sem origem: %s".formatted(stepCode, required.stream()
                        .filter(v -> !mapped.contains(v)).filter(Objects::nonNull).sorted()
                        .collect(Collectors.joining(", "))));
    }
}
