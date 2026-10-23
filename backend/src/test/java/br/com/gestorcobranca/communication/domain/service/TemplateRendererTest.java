package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.TemplateVariable;
import br.com.gestorcobranca.communication.domain.model.VariableType;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    private static final List<TemplateVariable> VARS = List.of(
            new TemplateVariable("nome", VariableType.FIRST_NAME, true, null),
            new TemplateVariable("valor", VariableType.MONEY, true, null),
            new TemplateVariable("vencimento", VariableType.DATE, true, null),
            new TemplateVariable("link", VariableType.URL, false, null));
    private static final String BODY = "Olá {{nome}}, sua parcela de {{valor}} vence em {{ vencimento }}. {{link}}";

    @Test
    void validatesDeclaredAndUsedVariables() {
        TemplateRenderer.validateDefinition(null, BODY, VARS);
        assertThatThrownBy(() -> TemplateRenderer.validateDefinition(null, "Olá {{nome}} {{outro}}",
                List.of(new TemplateVariable("nome", VariableType.FIRST_NAME, true, null))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "template.variable_undeclared");
        assertThatThrownBy(() -> TemplateRenderer.validateDefinition(null, "Olá", VARS.subList(0, 1)))
                .hasFieldOrPropertyWithValue("code", "template.variable_unused");
        assertThatThrownBy(() -> TemplateRenderer.validateDefinition(null, "{{nome}}", List.of(VARS.get(0), VARS.get(0))))
                .hasFieldOrPropertyWithValue("code", "template.variable_duplicated");
    }

    @Test
    void rendersTypedValuesInDeclaredOrder() {
        TemplateRenderer.Rendered r = TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Maria", "valor", "1234.5", "vencimento", "2026-11-05", "link", "https://pague.exemplo.com/x1"));
        assertThat(r.body()).isEqualTo("Olá Maria, sua parcela de R$ 1.234,50 vence em 05/11/2026. https://pague.exemplo.com/x1");
        assertThat(r.parameters()).containsExactly("Maria", "R$ 1.234,50", "05/11/2026", "https://pague.exemplo.com/x1");
    }

    @Test
    void optionalVariableMayBeOmittedButRequiredMayNot() {
        assertThat(TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Ana", "valor", "10", "vencimento", "2026-01-02")).parameters()).last().isEqualTo("");
        assertThatThrownBy(() -> TemplateRenderer.render(null, BODY, VARS, Map.of("nome", "Ana")))
                .hasFieldOrPropertyWithValue("code", "template.variable_required");
        assertThatThrownBy(() -> TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Ana", "valor", "10", "vencimento", "2026-01-02", "cpf", "x")))
                .hasFieldOrPropertyWithValue("code", "template.variable_unknown");
    }

    @Test
    void rejectsPersonalDataAndInvalidFormats() {
        List<TemplateVariable> text = List.of(new TemplateVariable("info", VariableType.TEXT, true, 80));
        for (String pii : List.of("CPF 529.982.247-25", "52998224725", "maria@exemplo.com", "(11) 98765-4321",
                "+55 11 987654321")) {
            assertThatThrownBy(() -> TemplateRenderer.render(null, "{{info}}", text, Map.of("info", pii)))
                    .as(pii).hasFieldOrPropertyWithValue("code", "template.variable_personal_data");
        }
        assertThatThrownBy(() -> TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Maria Silva", "valor", "10", "vencimento", "2026-01-02")))
                .hasFieldOrPropertyWithValue("code", "template.variable_first_name_invalid");
        assertThatThrownBy(() -> TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Maria", "valor", "10.123", "vencimento", "2026-01-02")))
                .hasFieldOrPropertyWithValue("code", "template.variable_money_invalid");
        assertThatThrownBy(() -> TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Maria", "valor", "10", "vencimento", "05/11/2026")))
                .hasFieldOrPropertyWithValue("code", "template.variable_date_invalid");
        assertThatThrownBy(() -> TemplateRenderer.render(null, BODY, VARS,
                Map.of("nome", "Maria", "valor", "10", "vencimento", "2026-01-02", "link", "http://inseguro.com")))
                .hasFieldOrPropertyWithValue("code", "template.variable_url_invalid");
        assertThatThrownBy(() -> TemplateRenderer.render(null, "{{info}}", text, Map.of("info", "linha\nquebrada")))
                .hasFieldOrPropertyWithValue("code", "template.variable_multiline");
    }

    @Test
    void codeAcceptsDigitableLineWithoutBeingTakenAsDocument() {
        List<TemplateVariable> code = List.of(new TemplateVariable("linha", VariableType.CODE, true, 60));
        String line = "34191.79001 01043.510047 91020.150008 1 96610000012345";
        assertThat(TemplateRenderer.render(null, "{{linha}}", code, Map.of("linha", line)).body()).isEqualTo(line);
    }
}
