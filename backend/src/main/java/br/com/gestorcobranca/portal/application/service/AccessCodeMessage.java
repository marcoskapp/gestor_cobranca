package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Variáveis da mensagem do código de acesso ({@code PORTAL_CODIGO_<CANAL>}). O código ({@code codigo})
 * vai sempre; a validade ({@code minutos}) só quando o template a declara — no WhatsApp o template é
 * de autenticação da Meta, com o código como único parâmetro e a validade configurada no próprio
 * template; no SMS e no e-mail a validade costuma estar no texto. Sem template publicado, envia as
 * duas e o communication recusa o envio ({@code message.template_not_found}), como antes.
 */
@Component
class AccessCodeMessage {

    static final String CODE = "codigo";
    static final String MINUTES = "minutos";

    private final TemplateUseCases templates;

    AccessCodeMessage(TemplateUseCases templates) {
        this.templates = templates;
    }

    Map<String, String> variables(UUID creditorId, String templateCode, String code, long minutes) {
        Set<String> declared = templates.findEffective(creditorId, templateCode)
                .map(t -> t.variables().stream().map(VariableInput::name).collect(Collectors.toSet()))
                .orElse(Set.of(CODE, MINUTES));
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CODE, code);
        if (declared.contains(MINUTES)) {
            values.put(MINUTES, String.valueOf(minutes));
        }
        return values;
    }
}
