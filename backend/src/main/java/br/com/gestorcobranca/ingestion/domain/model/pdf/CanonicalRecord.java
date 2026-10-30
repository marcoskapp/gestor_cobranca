package br.com.gestorcobranca.ingestion.domain.model.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Título do PDF já no layout canônico do ingestion: a mesma linha que um CSV do layout padrão teria. Valores
 * monetários lidos com o separador do mapa ficam em texto canônico ({@code 1234.56}, marcados em {@code typed}); os
 * demais são o texto do PDF.
 *
 * @param ordinal     posição no documento (1..n) — o número da linha na validação
 * @param debtorIndex bloco do devedor
 * @param origin      leitura do texto (determinística) ou visual
 * @param confidence  confiança declarada pelo modelo (só na leitura visual; 0 a 1)
 * @param info        informações adicionais ({@code info:*}), não enviadas ao lote
 * @param sources     campo (coluna canônica ou {@code info:*}) → regiões de origem
 * @param raw         campo → texto original do PDF (antes da conversão do valor)
 * @param lines       linhas físicas do título
 * @param page        página do título (leitura visual sem coordenadas)
 */
public record CanonicalRecord(int ordinal, int debtorIndex, RecordOrigin origin, Double confidence,
                              Map<CanonicalField, String> values, Set<CanonicalField> typed, Map<String, String> info,
                              Map<String, List<SourceRef>> sources, Map<String, String> raw, List<SourceRef> lines,
                              Integer page) {

    public CanonicalRecord {
        values = values.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(values));
        typed = typed.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(typed));
        info = Map.copyOf(info);
        sources = Map.copyOf(sources);
        raw = Map.copyOf(raw);
        lines = List.copyOf(lines);
    }

    /** Linha para o validador do ingestion (o mesmo de CSV/XLSX). */
    public SourceRow toSourceRow() {
        return new SourceRow(ordinal, values, typed);
    }
}
