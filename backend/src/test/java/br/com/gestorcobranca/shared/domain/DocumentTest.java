package br.com.gestorcobranca.shared.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTest {

    @Test
    void parsesFormattedCpfAndCnpj() {
        Document cpf = Document.parse("529.982.247-25");
        assertThat(cpf.type()).isEqualTo(Document.DocumentType.CPF);
        assertThat(cpf.number()).isEqualTo("52998224725");

        Document cnpj = Document.parse("11.222.333/0001-81");
        assertThat(cnpj.type()).isEqualTo(Document.DocumentType.CNPJ);
        assertThat(cnpj.formatted()).isEqualTo("11.222.333/0001-81");
    }

    @Test
    void rejectsInvalidCheckDigitsAndRepeatedDigits() {
        assertThatThrownBy(() -> Document.parse("529.982.247-24")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Document.parse("111.111.111-11")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Document.parse("11.222.333/0001-82")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Document.parse("123")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void neverExposesFullNumberInToString() {
        Document cpf = Document.parse("52998224725");
        assertThat(cpf.toString()).doesNotContain("52998224725").contains("***.982.247-**");
    }

    @ParameterizedTest
    @ValueSource(strings = {"52998224725-001", "0052998224725", "52998224725001",
            "529982247-25-001", "529.982.247.25", "CT 529 982 247 25 P1", "CT-0000052998224725", "52998224725/2",
            "X529.982.24725", "CT_529_982_247_25", "529:982:247:25", "529|982|247|25", "529\t982\t247\t25",
            "529\u00a0982\u00a0247\u00a025", "52998224725#1"})
    void embeddedCpfNeverStaysInClear(String reference) {
        String masked = Document.maskIfDocument(reference);
        assertThat(masked.replaceAll("\\D", "")).doesNotContain("52998224725");
        assertThat(masked).contains("*");
    }

    @Test
    void anyNumericPrefixNeverUncoversTheEmbeddedCpf() {
        for (int p = 0; p < 10_000; p++) {
            assertThat(Document.maskIfDocument(p + "52998224725").replaceAll("\\D", ""))
                    .as("prefixo %d", p).doesNotContain("52998224725");
            assertThat(Document.maskIfDocument(p + " | 529.982.247-25").replaceAll("\\D", ""))
                    .as("prefixo %d com separador", p).doesNotContain("52998224725");
        }
        // dígitos de largura total também
        assertThat(Document.maskIfDocument("CT ５２９９８２２４７２５")).contains("*");
    }

    @Test
    void aCoincidentCnpjWindowNeverLeavesTheRealCpfHalfVisible() {
        // procura um sufixo que faça CPF + sufixo formar um CNPJ válido (caso da janela que valeria por coincidência)
        String cpf = "52998224725";
        String suffix = java.util.stream.IntStream.range(0, 1000).mapToObj("%03d"::formatted)
                .filter(s -> Document.isValid(Document.DocumentType.CNPJ, cpf + s)).findFirst().orElseThrow();
        String masked = Document.maskIfDocument("X" + cpf + suffix);
        // os dígitos do CPF que a máscara esconde nunca aparecem: só o miolo (posições 3 a 8) pode ficar
        assertThat(masked.substring(1, 12)).matches("\\*\\*\\*\\d{6}\\*\\*");
    }

    @Test
    void detectsOnlyWholeTextDocumentsAndMasksEmbeddedOnes() {
        assertThat(Document.detect("529.982.247-25")).map(Document::number).contains("52998224725");
        assertThat(Document.detect("CPF 52998224725")).isEmpty();
        assertThat(Document.detect("12345")).isEmpty();
        assertThat(Document.maskIfDocument("529.982.247-25")).isEqualTo("***.982.247-**");
        // identificador de título do credor com o CPF embutido (SEG-P11): o documento não vai em claro ao credor
        assertThat(Document.maskIfDocument("52998224725-001")).isEqualTo("***982247**-001");
        assertThat(Document.maskIfDocument("CONTRATO 529.982.247-25 / 3")).isEqualTo("CONTRATO ***.982.247-** / 3");
        assertThat(Document.maskIfDocument("CNPJ11222333000181")).isEqualTo("CNPJ**2223330001**");
        assertThat(Document.maskIfDocument("CTR 11222333/0001-81")).isEqualTo("CTR **222333/0001-**");
        // sem documento válido, fica como está
        assertThat(Document.maskIfDocument("A-1")).isEqualTo("A-1");
        assertThat(Document.maskIfDocument("12345678901")).isEqualTo("12345678901");
        // CPF dentro de uma sequência maior também sai (parcela colada ao documento)
        assertThat(Document.maskIfDocument("529982247250001")).isEqualTo("***982247**0001");
        assertThat(Document.maskIfDocument(null)).isNull();
    }
}
