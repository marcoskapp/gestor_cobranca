package br.com.gestorcobranca.ingestion.domain.service.format;

import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountStyle;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnFormatStatus;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatSample;
import br.com.gestorcobranca.ingestion.domain.model.format.OptionSummary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Inferência da convenção numérica de <b>uma coluna</b> a partir de todos os seus valores ({@code importacao.md}
 * §2.2). Acumula em memória constante (contagens, amostras limitadas e somas por interpretação), o que permite
 * analisar um milhão de linhas na leitura prévia.
 */
public final class ColumnFormatAnalyzer {

    /** Exemplos guardados por classe de valor. */
    public static final int SAMPLES_PER_STYLE = 6;

    private final String column;
    private long typed;
    private long text;
    private final Map<AmountStyle, Long> counts = new EnumMap<>(AmountStyle.class);
    private final Map<AmountStyle, List<FormatSample>> samples = new EnumMap<>(AmountStyle.class);
    private final Map<FormatOption, BigDecimal> totals = new EnumMap<>(FormatOption.class);
    private final Map<FormatOption, Long> invalid = new EnumMap<>(FormatOption.class);

    public ColumnFormatAnalyzer(String column) {
        this.column = column;
        for (FormatOption option : FormatOption.values()) {
            totals.put(option, BigDecimal.ZERO);
            invalid.put(option, 0L);
        }
    }

    public void accept(long rowNumber, String raw, boolean isTyped) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (isTyped) {
            typed++;
            sample(rowNumber, raw, AmountStyle.TYPED);
            AmountReading reading = AmountParser.readTyped(raw);
            for (FormatOption option : FormatOption.values()) {
                add(option, reading);
            }
            return;
        }
        text++;
        AmountParser.Token token = AmountParser.lex(raw);
        counts.merge(token.style(), 1L, Long::sum);
        sample(rowNumber, raw.strip(), token.style());
        for (FormatOption option : FormatOption.values()) {
            add(option, AmountParser.convert(token, option.conventionFor(token.style())));
        }
    }

    private void add(FormatOption option, AmountReading reading) {
        if (reading.ok()) {
            totals.merge(option, reading.value(), BigDecimal::add);
        } else {
            invalid.merge(option, 1L, Long::sum);
        }
    }

    private void sample(long rowNumber, String raw, AmountStyle style) {
        List<FormatSample> list = samples.computeIfAbsent(style, s -> new ArrayList<>());
        if (list.size() < SAMPLES_PER_STYLE) {
            list.add(new FormatSample(rowNumber, raw.length() > 40 ? raw.substring(0, 40) : raw, style));
        }
    }

    public ColumnFormatAnalysis result() {
        long comma = counts.getOrDefault(AmountStyle.COMMA_DECIMAL, 0L);
        long dot = counts.getOrDefault(AmountStyle.DOT_DECIMAL, 0L);
        long ambiguousDot = counts.getOrDefault(AmountStyle.AMBIGUOUS_DOT, 0L);
        long ambiguousComma = counts.getOrDefault(AmountStyle.AMBIGUOUS_COMMA, 0L);
        long ambiguous = ambiguousDot + ambiguousComma;

        ColumnFormatStatus status;
        FormatOption recommended = null;
        List<FormatOption> allowed = new ArrayList<>();
        if ((comma > 0 && dot > 0) || (ambiguousDot > 0 && ambiguousComma > 0)) {
            // "1.234,56" e "1,234.56" (ou "1.000" e "1,000") na mesma coluna: só uma delas pode estar certa por convenção
            status = ColumnFormatStatus.MIXED;
            allowed.addAll(List.of(FormatOption.values()));
        } else if (comma > 0 || dot > 0) {
            recommended = comma > 0 ? FormatOption.COMMA : FormatOption.DOT;
            status = ambiguous > 0 ? ColumnFormatStatus.INFERRED : ColumnFormatStatus.DETECTED;
            allowed.add(recommended);
            FormatOption other = recommended == FormatOption.COMMA ? FormatOption.DOT : FormatOption.COMMA;
            if (invalid.get(other).equals(invalid.get(recommended))) {
                // a alternativa só é oferecida se não invalidar nenhum valor que a recomendada lê
                allowed.add(other);
            }
        } else if (ambiguous > 0) {
            status = ColumnFormatStatus.AMBIGUOUS;
            allowed.addAll(List.of(FormatOption.COMMA, FormatOption.DOT));
        } else {
            status = ColumnFormatStatus.NOT_NEEDED;
            recommended = FormatOption.COMMA;
            allowed.add(FormatOption.COMMA);
        }
        List<OptionSummary> options = allowed.stream()
                .map(o -> new OptionSummary(o, totals.get(o), invalid.get(o)))
                .toList();
        List<FormatSample> all = new ArrayList<>();
        for (AmountStyle style : List.of(AmountStyle.AMBIGUOUS_DOT, AmountStyle.AMBIGUOUS_COMMA, AmountStyle.COMMA_DECIMAL,
                AmountStyle.DOT_DECIMAL, AmountStyle.PLAIN, AmountStyle.TYPED, AmountStyle.INVALID)) {
            all.addAll(samples.getOrDefault(style, List.of()));
        }
        return new ColumnFormatAnalysis(column, status, typed, text, counts, all, options, allowed, recommended);
    }
}
