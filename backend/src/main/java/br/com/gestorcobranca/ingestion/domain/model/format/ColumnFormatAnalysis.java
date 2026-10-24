package br.com.gestorcobranca.ingestion.domain.model.format;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resultado da análise do formato numérico de uma coluna monetária na leitura prévia (todas as linhas).
 *
 * @param column      nome canônico da coluna ({@code valor_principal})
 * @param typedCount  valores tipados (célula numérica do XLSX, número JSON)
 * @param textCount   valores em texto (CSV, texto no XLSX — "número armazenado como texto" —, string JSON)
 * @param styleCounts valores em texto por classe
 * @param samples     exemplos reais por classe (linha e texto original)
 * @param options     efeito de cada interpretação na coluna inteira (soma e inválidos)
 * @param allowed     interpretações oferecidas ao usuário
 * @param recommended interpretação indicada pelos dados; nula quando a coluna exige decisão
 */
public record ColumnFormatAnalysis(String column, ColumnFormatStatus status, long typedCount, long textCount,
                                   Map<AmountStyle, Long> styleCounts, List<FormatSample> samples,
                                   List<OptionSummary> options, List<FormatOption> allowed, FormatOption recommended) {

    public ColumnFormatAnalysis {
        styleCounts = Map.copyOf(styleCounts);
        samples = List.copyOf(samples);
        options = List.copyOf(options);
        allowed = List.copyOf(allowed);
    }

    public boolean requiresDecision() {
        return status.requiresDecision();
    }

    public Optional<OptionSummary> option(FormatOption option) {
        return options.stream().filter(o -> o.option() == option).findFirst();
    }

    public long count(AmountStyle style) {
        return styleCounts.getOrDefault(style, 0L);
    }

    /** Decisão automática (sem intervenção do usuário), quando a coluna não exige decisão. */
    public Optional<ColumnDecision> autoDecision() {
        if (requiresDecision()) {
            return Optional.empty();
        }
        DecisionSource source = switch (status) {
            case NOT_NEEDED -> DecisionSource.NOT_NEEDED;
            case INFERRED -> DecisionSource.INFERRED;
            default -> DecisionSource.DETECTED;
        };
        return Optional.of(new ColumnDecision(recommended, source));
    }
}
