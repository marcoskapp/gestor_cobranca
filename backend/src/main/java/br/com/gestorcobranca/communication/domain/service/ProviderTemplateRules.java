package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ProviderCategory;
import br.com.gestorcobranca.communication.domain.model.TemplatePurpose;
import br.com.gestorcobranca.communication.domain.model.TemplateVariable;
import br.com.gestorcobranca.communication.domain.model.VariableType;
import br.com.gestorcobranca.shared.domain.Guard;

import java.util.List;

/**
 * Regras do formato exigido pelo provedor para a categoria do template.
 * <ul>
 *   <li>Categoria só no WhatsApp (nos demais canais é nula).</li>
 *   <li>{@link ProviderCategory#AUTHENTICATION}: exatamente uma variável, do tipo {@code CODE},
 *       obrigatória e com até {@value #OTP_MAX_LENGTH} caracteres (limite da Meta para o código) — o
 *       mesmo valor vai no corpo e no botão "copiar código".</li>
 * </ul>
 */
public final class ProviderTemplateRules {

    /** Tamanho máximo do código num template de autenticação da Meta. */
    public static final int OTP_MAX_LENGTH = 15;

    /** Corpo máximo de um template de finalidade autenticação (o código e, no máximo, a validade). */
    public static final int AUTHENTICATION_BODY_MAX = 300;

    /**
     * Finalidade autenticação (código de acesso, decisão 1084): a isenção de horário do código vale para o código, não
     * para um texto qualquer. Corpo de até {@value #AUTHENTICATION_BODY_MAX} caracteres; uma variável {@code CODE}
     * obrigatória e, no máximo, uma {@code INTEGER} (a validade); no WhatsApp, categoria {@code AUTHENTICATION} (que a
     * Meta limita ao código). A categoria de autenticação, por sua vez, só com essa finalidade.
     */
    public static void validatePurpose(ChannelType channel, TemplatePurpose purpose, ProviderCategory category,
                                       String subject, String body, List<TemplateVariable> variables) {
        if (purpose != TemplatePurpose.AUTHENTICATION) {
            Guard.require(category != ProviderCategory.AUTHENTICATION, "template.authentication_purpose_required",
                    "Template na categoria de autenticação do provedor precisa ter a finalidade Autenticação");
            return;
        }
        Guard.require(body != null && body.length() <= AUTHENTICATION_BODY_MAX, "template.authentication_body_too_long",
                "Template de autenticação com corpo de até %d caracteres".formatted(AUTHENTICATION_BODY_MAX));
        // o assunto aparece nas telas: o código vai só no corpo
        Guard.require(subject == null || TemplateRenderer.placeholders(subject).isEmpty(),
                "template.authentication_subject_variables",
                "No template de autenticação, o assunto não leva variáveis (o código vai só no corpo)");
        long codes = variables.stream().filter(v -> v.type() == VariableType.CODE && v.required()).count();
        boolean onlyCodeAndValidity = variables.stream()
                .allMatch(v -> v.type() == VariableType.CODE || v.type() == VariableType.INTEGER);
        long integers = variables.stream().filter(v -> v.type() == VariableType.INTEGER).count();
        Guard.require(codes == 1 && variables.stream().filter(v -> v.type() == VariableType.CODE).count() == 1
                        && onlyCodeAndValidity && integers <= 1, "template.authentication_variables",
                "Template de autenticação tem só o código (tipo CODE, obrigatório) e, no máximo, a validade (INTEGER)");
        Guard.require(channel != ChannelType.WHATSAPP || category == ProviderCategory.AUTHENTICATION,
                "template.authentication_category_required",
                "No WhatsApp, o template de autenticação usa a categoria de autenticação do provedor");
    }

    private ProviderTemplateRules() {
    }

    public static void validate(ChannelType channel, ProviderCategory category, List<TemplateVariable> variables) {
        if (channel != ChannelType.WHATSAPP) {
            Guard.require(category == null, "template.category_not_applicable",
                    "Categoria no provedor só se aplica a templates de WhatsApp");
            return;
        }
        if (category != ProviderCategory.AUTHENTICATION) {
            return;
        }
        Guard.require(variables.size() == 1, "template.authentication_variables",
                "Template de autenticação tem uma única variável: o código");
        TemplateVariable code = variables.getFirst();
        Guard.require(code.type() == VariableType.CODE && code.required(), "template.authentication_variables",
                "A variável do template de autenticação é o código (tipo CODE, obrigatória)");
        Guard.require(code.maxLength() == null || code.maxLength() <= OTP_MAX_LENGTH, "template.authentication_code_length",
                "O código do template de autenticação tem no máximo %d caracteres".formatted(OTP_MAX_LENGTH));
    }
}
