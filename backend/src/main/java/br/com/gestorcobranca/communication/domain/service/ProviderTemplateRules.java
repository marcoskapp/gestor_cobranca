package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ProviderCategory;
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
