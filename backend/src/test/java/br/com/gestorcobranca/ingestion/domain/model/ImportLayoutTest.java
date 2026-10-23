package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportLayoutTest {

    private static final UUID CREDITOR = UUID.randomUUID();

    private static List<FileLayout.Column> headers() {
        return List.of(new FileLayout.Column(CanonicalField.DEBTOR_DOCUMENT, "CPF/CNPJ", null),
                new FileLayout.Column(CanonicalField.DEBTOR_NAME, "Nome do Cliente", null),
                new FileLayout.Column(CanonicalField.TITLE_EXTERNAL_ID, "Nosso Número", null),
                new FileLayout.Column(CanonicalField.DUE_DATE, "Vencimento", null),
                new FileLayout.Column(CanonicalField.PRINCIPAL_AMOUNT, "Valor", null));
    }

    @Test
    void createsVersionedLayoutRestrictedToPortfolio() {
        UUID portfolio = UUID.randomUUID();
        ImportLayout layout = new ImportLayout(CREDITOR, portfolio, "banco_x", 2, "Banco X", ImportFormat.CSV, ';',
                "windows-1252", true, null, headers());
        assertThat(layout.getCode()).isEqualTo("BANCO_X");
        assertThat(layout.getCsvEncoding()).isEqualTo("WINDOWS-1252");
        assertThat(layout.appliesTo(CREDITOR, portfolio)).isTrue();
        assertThat(layout.appliesTo(CREDITOR, UUID.randomUUID())).isFalse();
        FileLayout file = layout.toFileLayout();
        assertThat(file.version()).isEqualTo(2);
        assertThat(file.columns()).hasSize(5);
    }

    @Test
    void requiresAllMandatoryFieldsAndUniqueHeaders() {
        assertThatThrownBy(() -> new ImportLayout(CREDITOR, null, "X", 1, "X", ImportFormat.CSV, ';', "UTF-8", true, null,
                headers().subList(0, 3))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "import_layout.required_fields_missing");
        List<FileLayout.Column> repeated = new java.util.ArrayList<>(headers());
        repeated.add(new FileLayout.Column(CanonicalField.PRODUCT, "valor ", null));
        assertThatThrownBy(() -> new ImportLayout(CREDITOR, null, "X", 1, "X", ImportFormat.CSV, ';', "UTF-8", true, null,
                repeated)).hasFieldOrPropertyWithValue("code", "import_layout.header_repeated");
        assertThatThrownBy(() -> new ImportLayout(CREDITOR, null, "PADRAO", 1, "X", ImportFormat.CSV, ';', "UTF-8", true,
                null, headers())).hasFieldOrPropertyWithValue("code", "import_layout.code_reserved");
        assertThatThrownBy(() -> new ImportLayout(CREDITOR, null, "X", 1, "X", ImportFormat.CSV, '#', "UTF-8", true,
                null, headers())).hasFieldOrPropertyWithValue("code", "import_layout.delimiter_invalid");
    }
}
