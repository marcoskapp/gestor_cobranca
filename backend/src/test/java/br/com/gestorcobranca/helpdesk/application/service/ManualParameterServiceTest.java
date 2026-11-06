package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Agregação dos parâmetros do manual publicados pelos módulos: chave {@code <modulo>.<nome>} e valores válidos. */
class ManualParameterServiceTest {

    private static ManualParameterSource source(String module, Map<String, BigDecimal> values) {
        return new ManualParameterSource() {
            @Override
            public String module() {
                return module;
            }

            @Override
            public Map<String, BigDecimal> parameters() {
                return values;
            }
        };
    }

    @Test
    void prefixesEachKeyWithTheOwnerModule() {
        var service = new ManualParameterService(List.of(
                source("debt", Map.of("cumulativeWindowDays", BigDecimal.valueOf(30))),
                source("settlement", Map.of("adjustmentDualApprovalThreshold", new BigDecimal("1000.00")))));
        assertThat(service.current().values()).containsOnly(
                Map.entry("debt.cumulativeWindowDays", BigDecimal.valueOf(30)),
                Map.entry("settlement.adjustmentDualApprovalThreshold", new BigDecimal("1000.00")));
    }

    @Test
    void invalidSourcePreventsStartup() {
        assertThatThrownBy(() -> new ManualParameterService(List.of(
                source("debt", Map.of("x", BigDecimal.ONE)), source("debt", Map.of("x", BigDecimal.TEN)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("repetida debt.x");
        assertThatThrownBy(() -> new ManualParameterService(List.of(source("Debt", Map.of("x", BigDecimal.ONE)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("módulo inválido");
        assertThatThrownBy(() -> new ManualParameterService(List.of(source("debt", Map.of("dias.x", BigDecimal.ONE)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chave inválida");
        assertThatThrownBy(() -> new ManualParameterService(List.of(source("debt", Map.of("x", BigDecimal.valueOf(-1))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("negativo");
    }
}
