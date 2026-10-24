package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision;
import br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import br.com.gestorcobranca.ingestion.domain.model.review.RowValue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RowValueExtractorTest {

    private static final NumberFormat COMMA = new NumberFormat(Map.of("valor_principal",
            new ColumnDecision(FormatOption.COMMA, DecisionSource.USER)));

    @Test
    void extractsValuesWithoutPersonalDataAndFlagsAmbiguousAndInvalid() {
        Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
        values.put(CanonicalField.DEBTOR_DOCUMENT, "529.982.247-25");
        values.put(CanonicalField.DEBTOR_NAME, "Maria");
        values.put(CanonicalField.TITLE_EXTERNAL_ID, "T-1");
        values.put(CanonicalField.PRINCIPAL_AMOUNT, "1.000");
        values.put(CanonicalField.REPORTED_BALANCE, "abc");
        values.put(CanonicalField.DUE_DATE, "10/03/2026");
        values.put(CanonicalField.TITLE_SITUATION, "quitado");
        values.put(CanonicalField.PRODUCT, "Cartão");
        RowValue v = RowValueExtractor.extract(new SourceRow(9, values, Set.of()), false, COMMA, doc -> "key:" + doc.length());
        assertThat(v.rowNumber()).isEqualTo(9);
        assertThat(v.valid()).isFalse();
        assertThat(v.principal()).isEqualByComparingTo("1000");
        assertThat(v.principalAmbiguous()).isTrue();
        assertThat(v.balance()).isNull();
        assertThat(v.balanceInvalid()).isTrue();
        assertThat(v.dueDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(v.situation()).isEqualTo("PAID");
        assertThat(v.product()).isEqualTo("Cartão");
        assertThat(v.externalId()).isEqualTo("T-1");
        assertThat(v.debtorKey()).isEqualTo("key:14");
    }

    @Test
    void missingValuesAreNeitherSummedNorInvalid() {
        RowValue v = RowValueExtractor.extract(new SourceRow(2, Map.of(CanonicalField.TITLE_SITUATION, "???")), true,
                NumberFormat.empty(), doc -> doc);
        assertThat(v.principal()).isNull();
        assertThat(v.principalInvalid()).isFalse();
        assertThat(v.situation()).isEqualTo("INVALID");
        assertThat(v.debtorKey()).isNull();
    }
}
