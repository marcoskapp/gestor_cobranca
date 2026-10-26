package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ProviderCategory;
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
}
