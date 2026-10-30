package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;
import java.util.Map;

/**
 * Resultado da aplicação de um mapa à camada de texto: blocos de devedor, títulos, totais de controle e campos do
 * documento, sempre com a origem no PDF, mais as regiões lidas (para o destaque na tela) e as linhas não reconhecidas.
 *
 * @param headerRegions  cabeçalhos da tabela encontrados
 * @param tableHeaderFound o cabeçalho da tabela foi encontrado ao menos uma vez
 */
public record Extraction(List<DebtorBlock> debtors, List<ExtractedRecord> records, List<ControlValue> controls,
                         Map<String, CellValue> documentFields, List<SourceRef> headerRegions,
                         List<LineNote> unrecognized, boolean tableHeaderFound) {

    public Extraction {
        debtors = List.copyOf(debtors);
        records = List.copyOf(records);
        controls = List.copyOf(controls);
        documentFields = Map.copyOf(documentFields);
        headerRegions = List.copyOf(headerRegions);
        unrecognized = List.copyOf(unrecognized);
    }
}
