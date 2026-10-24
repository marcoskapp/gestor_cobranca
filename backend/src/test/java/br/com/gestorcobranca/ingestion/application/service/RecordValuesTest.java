package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordValuesTest {

    @Test
    void numbersBecomeExactDecimalsAndStringsStayText() {
        Map<String, Object> record = new HashMap<>();
        record.put("valor_principal", 1234.56d);
        record.put("saldo", "1.000");
        record.put("parcela", 3);
        record.put("valor_contrato", 12345678901L);
        record.put("titulo_executivo", true);
        record.put("vazio", null);
        Map<String, Object> clean = RecordValues.canonical(List.of(record)).getFirst();
        assertThat((BigDecimal) clean.get("valor_principal")).isEqualByComparingTo("1234.56");
        assertThat(clean.get("saldo")).isEqualTo("1.000");
        assertThat((BigDecimal) clean.get("parcela")).isEqualByComparingTo("3");
        assertThat((BigDecimal) clean.get("valor_contrato")).isEqualByComparingTo("12345678901");
        assertThat(clean.get("titulo_executivo")).isEqualTo("true");
        assertThat(clean).doesNotContainKey("vazio");
    }

    @Test
    void refusesNestedValuesAndImpreciseNumbers() {
        assertThatThrownBy(() -> RecordValues.canonical(List.of(Map.of("valor_principal", Map.of("a", 1)))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.record_value_invalid");
        assertThatThrownBy(() -> RecordValues.canonical(List.of(Map.of("valor_principal", 1234567890123456.7d))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.record_number_precision");
        assertThatThrownBy(() -> RecordValues.canonical(List.of(Map.of("valor_principal", Double.NaN))))
                .isInstanceOf(BusinessRuleException.class);
    }
}
