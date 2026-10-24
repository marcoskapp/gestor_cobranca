package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.model.TitleSituation;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import br.com.gestorcobranca.ingestion.domain.model.review.RowValue;
import br.com.gestorcobranca.ingestion.domain.service.BrazilianFormats;
import br.com.gestorcobranca.ingestion.domain.service.format.AmountParser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

/**
 * Extrai os valores de uma linha (válida ou não) para os totais e a análise da conferência, com a mesma leitura de
 * valores do validador. Função pura.
 */
public final class RowValueExtractor {

    private static final int TEXT_MAX = 80;
    private static final int PRODUCT_MAX = 120;

    private RowValueExtractor() {
    }

    /**
     * @param debtorKey transforma o documento informado na chave do devedor (blind index), ou nulo se inválido
     */
    public static RowValue extract(SourceRow row, boolean valid, NumberFormat format, UnaryOperator<String> debtorKey) {
        Amount principal = amount(row, CanonicalField.PRINCIPAL_AMOUNT, format);
        Amount balance = amount(row, CanonicalField.REPORTED_BALANCE, format);
        Amount contract = amount(row, CanonicalField.CONTRACT_AMOUNT, format);
        LocalDate due = row.get(CanonicalField.DUE_DATE).flatMap(BrazilianFormats::parseDate).orElse(null);
        String situation = row.get(CanonicalField.TITLE_SITUATION)
                .map(raw -> TitleSituation.parse(raw).map(Enum::name).orElse("INVALID"))
                .orElse(TitleSituation.ACTIVE.name());
        String document = row.get(CanonicalField.DEBTOR_DOCUMENT).orElse(null);
        return new RowValue(row.rowNumber(), valid, principal.value, principal.invalid, principal.ambiguous,
                balance.value, balance.invalid, contract.value, contract.invalid, due, situation,
                text(row, CanonicalField.PRODUCT, PRODUCT_MAX), text(row, CanonicalField.CONTRACT_NUMBER, TEXT_MAX),
                text(row, CanonicalField.TITLE_EXTERNAL_ID, TEXT_MAX),
                document == null ? null : debtorKey.apply(document));
    }

    private record Amount(BigDecimal value, boolean invalid, boolean ambiguous) {
    }

    private static Amount amount(SourceRow row, CanonicalField field, NumberFormat format) {
        String raw = row.get(field).orElse(null);
        if (raw == null) {
            return new Amount(null, false, false);
        }
        AmountReading reading = AmountParser.read(raw, row.isTyped(field), format.option(field));
        return new Amount(reading.value(), !reading.ok(), reading.style() != null && reading.style().isAmbiguous());
    }

    private static String text(SourceRow row, CanonicalField field, int max) {
        return row.get(field).map(v -> v.length() <= max ? v : v.substring(0, max)).orElse(null);
    }
}
