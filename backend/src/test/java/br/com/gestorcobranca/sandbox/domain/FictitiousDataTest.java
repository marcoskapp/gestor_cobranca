package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.sandbox.domain.service.CnjNumbers;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.shared.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FictitiousDataTest {

    @Test
    void documentsHaveValidCheckDigits() {
        FictitiousData data = new FictitiousData(42);
        IntStream.range(0, 500).forEach(i -> {
            assertThat(Document.isValid(Document.DocumentType.CPF, data.cpf())).isTrue();
            assertThat(Document.isValid(Document.DocumentType.CNPJ, data.cnpj())).isTrue();
        });
    }

    @Test
    void sameSeedProducesSameData() {
        FictitiousData a = new FictitiousData(7);
        FictitiousData b = new FictitiousData(7);
        for (int i = 0; i < 50; i++) {
            assertThat(a.cpf()).isEqualTo(b.cpf());
            assertThat(a.personName()).isEqualTo(b.personName());
            assertThat(a.mobilePhone()).isEqualTo(b.mobilePhone());
        }
    }

    @Test
    void contactsUseReservedDomainsAndBrazilianMobileFormat() {
        FictitiousData data = new FictitiousData(1);
        assertThat(data.email("Fábio Guimarães Holanda", 12)).isEqualTo("fabio.holanda.12@exemplo.test");
        IntStream.range(0, 100).forEach(i -> assertThat(data.mobilePhone()).matches("\\+55\\d{2}99\\d{7}"));
        IntStream.range(0, 100).forEach(i -> assertThat(data.plate()).matches("[A-Z]{3}\\d[A-Z]\\d{2}"));
        assertThat(data.birthDate(LocalDate.of(2026, 10, 30))).isBefore(LocalDate.of(2005, 11, 1));
    }

    @Test
    void cnjNumbersHaveValidCheckDigits() {
        String number = CnjNumbers.format(1234, 2026, 8, 26, 100);
        assertThat(number).matches("\\d{7}-\\d{2}\\.2026\\.8\\.26\\.0100");
        assertThat(CnjNumbers.isValid(number)).isTrue();
        String tampered = number.substring(0, 8) + (number.charAt(8) == '9' ? '0' : (char) (number.charAt(8) + 1))
                + number.substring(9);
        assertThat(CnjNumbers.isValid(tampered)).isFalse();
        // mesmo cálculo da validação do módulo legal (o cadastro de ações recusa número inválido)
        assertThat(number.substring(8, 10)).isEqualTo(br.com.gestorcobranca.legal.domain.model.CnjNumber
                .checkDigitsFor("0001234", "2026", "8", "26", "0100"));
        IntStream.range(0, 200).forEach(i -> assertThat(CnjNumbers.isValid(
                CnjNumbers.format(1_000_000L + i * 37L, 2025, 8, 26, 100))).isTrue());
    }
}
