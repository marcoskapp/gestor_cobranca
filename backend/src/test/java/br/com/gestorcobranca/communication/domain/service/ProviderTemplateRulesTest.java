package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ProviderCategory;
import br.com.gestorcobranca.communication.domain.model.TemplatePurpose;
import br.com.gestorcobranca.communication.domain.model.TemplateVariable;
import br.com.gestorcobranca.communication.domain.model.VariableType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Formato do template de autenticação da Meta: um único parâmetro, o código. */
class ProviderTemplateRulesTest {

    private static final TemplateVariable CODE = new TemplateVariable("codigo", VariableType.CODE, true, null);
    private static final TemplateVariable MINUTES = new TemplateVariable("minutos", VariableType.INTEGER, true, null);

    @Test
    void authenticationTemplateHasOnlyTheCode() {
        assertThatCode(() -> ProviderTemplateRules.validate(ChannelType.WHATSAPP, ProviderCategory.AUTHENTICATION,
                List.of(CODE))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProviderTemplateRules.validate(ChannelType.WHATSAPP, ProviderCategory.AUTHENTICATION,
                List.of(CODE, MINUTES))).hasFieldOrPropertyWithValue("code", "template.authentication_variables");
        assertThatThrownBy(() -> ProviderTemplateRules.validate(ChannelType.WHATSAPP, ProviderCategory.AUTHENTICATION,
                List.of(new TemplateVariable("codigo", VariableType.TEXT, true, null))))
                .hasFieldOrPropertyWithValue("code", "template.authentication_variables");
        assertThatThrownBy(() -> ProviderTemplateRules.validate(ChannelType.WHATSAPP, ProviderCategory.AUTHENTICATION,
                List.of(new TemplateVariable("codigo", VariableType.CODE, false, null))))
                .hasFieldOrPropertyWithValue("code", "template.authentication_variables");
        assertThatThrownBy(() -> ProviderTemplateRules.validate(ChannelType.WHATSAPP, ProviderCategory.AUTHENTICATION,
                List.of(new TemplateVariable("codigo", VariableType.CODE, true, 20))))
                .hasFieldOrPropertyWithValue("code", "template.authentication_code_length");
    }

    @Test
    void utilityTemplatesKeepAnyVariablesAndOtherChannelsHaveNoCategory() {
        assertThatCode(() -> ProviderTemplateRules.validate(ChannelType.WHATSAPP, ProviderCategory.UTILITY,
                List.of(CODE, MINUTES))).doesNotThrowAnyException();
        assertThatCode(() -> ProviderTemplateRules.validate(ChannelType.SMS, null, List.of(CODE, MINUTES)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProviderTemplateRules.validate(ChannelType.EMAIL, ProviderCategory.AUTHENTICATION,
                List.of(CODE))).hasFieldOrPropertyWithValue("code", "template.category_not_applicable");
    }

    /** Finalidade autenticação (decisão 1084): corpo curto, só o código e a validade; no WhatsApp, categoria da Meta. */
    @Test
    void authenticationPurposeKeepsTheMessageToTheCode() {
        String body = "Seu código é {{codigo}}. Vale por {{minutos}} minutos.";
        assertThatCode(() -> ProviderTemplateRules.validatePurpose(ChannelType.EMAIL, TemplatePurpose.AUTHENTICATION, null,
                "Seu código", body, List.of(CODE, MINUTES))).doesNotThrowAnyException();
        assertThatCode(() -> ProviderTemplateRules.validatePurpose(ChannelType.WHATSAPP, TemplatePurpose.AUTHENTICATION,
                ProviderCategory.AUTHENTICATION, null, "{{codigo}} é o seu código.", List.of(CODE))).doesNotThrowAnyException();
        // texto de cobrança cabe só em template comum
        assertThatThrownBy(() -> ProviderTemplateRules.validatePurpose(ChannelType.SMS, TemplatePurpose.AUTHENTICATION,
                null, null, "x".repeat(301), List.of(CODE))).hasFieldOrPropertyWithValue("code", "template.authentication_body_too_long");
        assertThatThrownBy(() -> ProviderTemplateRules.validatePurpose(ChannelType.EMAIL, TemplatePurpose.AUTHENTICATION,
                null, null, body, List.of(CODE, new TemplateVariable("valor", VariableType.MONEY, true, null))))
                .hasFieldOrPropertyWithValue("code", "template.authentication_variables");
        assertThatThrownBy(() -> ProviderTemplateRules.validatePurpose(ChannelType.EMAIL, TemplatePurpose.AUTHENTICATION,
                null, null, "Olá", List.of())).hasFieldOrPropertyWithValue("code", "template.authentication_variables");
        assertThatThrownBy(() -> ProviderTemplateRules.validatePurpose(ChannelType.WHATSAPP, TemplatePurpose.AUTHENTICATION,
                ProviderCategory.UTILITY, null, "{{codigo}}", List.of(CODE)))
                .hasFieldOrPropertyWithValue("code", "template.authentication_category_required");
        // a categoria de autenticação do provedor só com a finalidade autenticação
        assertThatThrownBy(() -> ProviderTemplateRules.validatePurpose(ChannelType.WHATSAPP, TemplatePurpose.SERVICE,
                ProviderCategory.AUTHENTICATION, null, "{{codigo}}", List.of(CODE)))
                .hasFieldOrPropertyWithValue("code", "template.authentication_purpose_required");
    }

    /** O assunto aparece nas telas: no template de autenticação ele não leva variáveis (decisão 1084). */
    @Test
    void authenticationSubjectHasNoVariables() {
        assertThatThrownBy(() -> ProviderTemplateRules.validatePurpose(ChannelType.EMAIL, TemplatePurpose.AUTHENTICATION,
                null, "Seu código: {{codigo}}", "Use {{codigo}}.", List.of(CODE)))
                .hasFieldOrPropertyWithValue("code", "template.authentication_subject_variables");
    }
}
