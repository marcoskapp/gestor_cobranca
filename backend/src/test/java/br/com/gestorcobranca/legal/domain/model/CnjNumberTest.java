package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CnjNumberTest {

    @Test
    void parsesValidNumbersWithOrWithoutMask() {
        CnjNumber n = CnjNumber.parse("0001234-03.2026.8.26.0100");
        assertThat(n.digits()).isEqualTo("00012340320268260100");
        assertThat(n.year()).isEqualTo(2026);
        assertThat(n.segment()).isEqualTo(8);
        assertThat(n.courtCode()).isEqualTo("8.26");
        assertThat(n.origin()).isEqualTo("0100");
        assertThat(n.formatted()).isEqualTo("0001234-03.2026.8.26.0100");
        assertThat(CnjNumber.parse("10000018620258190001").formatted()).isEqualTo("1000001-86.2025.8.19.0001");
        assertThat(CnjNumber.parse("0700000-30.2024.4.03.6100").courtCode()).isEqualTo("4.03");
    }

    @Test
    void computesCheckDigits() {
        assertThat(CnjNumber.checkDigitsFor("0001234", "2026", "8", "26", "0100")).isEqualTo("03");
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThatThrownBy(() -> CnjNumber.parse("0001234-04.2026.8.26.0100"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("verificador");
        assertThatThrownBy(() -> CnjNumber.parse("0001234-03.2026.8.26.010"))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("lawsuit.cnj_invalid");
        assertThatThrownBy(() -> CnjNumber.parse("0001234-03.2026.0.26.0100"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Segmento");
        assertThatThrownBy(() -> CnjNumber.parse("abc")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> CnjNumber.parse(null)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("lawsuit.cnj_required");
    }
}
