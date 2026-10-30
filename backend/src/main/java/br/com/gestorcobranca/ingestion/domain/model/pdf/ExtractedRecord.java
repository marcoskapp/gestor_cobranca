package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;
import java.util.Map;

/**
 * Título encontrado na tabela, antes do mapeamento para o layout canônico: o texto de cada coluna (pelo rótulo do
 * cabeçalho) e as linhas físicas que o formam.
 *
 * @param ordinal     posição do título no documento (1..n)
 * @param debtorIndex bloco do devedor (0..n-1)
 * @param cells       coluna → texto lido (colunas sem texto não aparecem)
 * @param lines       linhas físicas do título
 */
public record ExtractedRecord(int ordinal, int debtorIndex, Map<String, CellValue> cells, List<SourceRef> lines) {

    public ExtractedRecord {
        cells = Map.copyOf(cells);
        lines = List.copyOf(lines);
    }
}
