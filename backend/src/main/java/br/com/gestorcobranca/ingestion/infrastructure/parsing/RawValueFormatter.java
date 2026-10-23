package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Converte células numéricas do XLSX em texto independente da formatação visual da planilha
 * (moeda, separador de milhar, localidade): datas viram ISO ({@code aaaa-MM-dd}) e números o
 * valor exato com até 15 dígitos significativos — a precisão do Excel, o que elimina resíduos de
 * ponto flutuante (0,30000000000000004 → 0.3) sem arredondar valores reais.
 */
final class RawValueFormatter extends DataFormatter {

    private static final MathContext EXCEL_PRECISION = new MathContext(15);

    @Override
    public String formatRawCellContents(double value, int formatIndex, String formatString) {
        if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
            return DateUtil.getLocalDateTime(value).toLocalDate().toString();
        }
        return new BigDecimal(value, EXCEL_PRECISION).stripTrailingZeros().toPlainString();
    }
}
