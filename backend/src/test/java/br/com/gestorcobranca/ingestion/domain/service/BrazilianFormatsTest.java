package br.com.gestorcobranca.ingestion.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BrazilianFormatsTest {

    @ParameterizedTest
    @CsvSource({"10/03/2026,2026-03-10", "1/3/2026,2026-03-01", "10-03-2026,2026-03-10", "2026-03-10,2026-03-10",
            "2026-03-10T14:30:00,2026-03-10", "2026-03-10 14:30,2026-03-10"})
    void parsesAcceptedDateFormats(String raw, String expected) {
        assertThat(BrazilianFormats.parseDate(raw)).contains(LocalDate.parse(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31/02/2026", "10/03/26", "2026/03/10", "1899-12-31", "2200-01-01", "amanhã", "10.03.2026"})
    void rejectsInvalidOrAmbiguousDates(String raw) {
        assertThat(BrazilianFormats.parseDate(raw)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"1.234,56|1234.56", "1234,56|1234.56", "1234.56|1234.56", "1234|1234",
            "R$ 1.234,56|1234.56", "1.234|1234", "1.234.567,8|1234567.8", "-10,00|-10.00", "0,5|0.5"})
    void parsesAmounts(String raw, String expected) {
        assertThat(BrazilianFormats.parseAmount(raw)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,234.56", "12,345", "1.23.4", "abc", "1.2345", "123456789012345678"})
    void rejectsAmbiguousOrInvalidAmounts(String raw) {
        assertThat(BrazilianFormats.parseAmount(raw)).isEmpty();
    }

    @Test
    void parsesIntegersAndFlags() {
        assertThat(BrazilianFormats.parseInteger("003")).contains(3);
        assertThat(BrazilianFormats.parseInteger("12.0")).contains(12);
        assertThat(BrazilianFormats.parseInteger("-1")).isEmpty();
        assertThat(BrazilianFormats.parseFlag("Sim")).contains(true);
        assertThat(BrazilianFormats.parseFlag("não")).contains(false);
        assertThat(BrazilianFormats.parseFlag("talvez")).isEmpty();
        assertThat(BrazilianFormats.parseAmount("1.234,56").orElseThrow()).isEqualTo(new BigDecimal("1234.56"));
    }
}
