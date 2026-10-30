package br.com.gestorcobranca.ingestion.domain.model.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;

/**
 * Total de controle encontrado no documento.
 *
 * @param debtorIndex bloco em que apareceu (-1 = antes de qualquer bloco)
 * @param definition  total do mapa
 * @param value       texto lido e a sua origem
 */
public record ControlValue(int debtorIndex, ControlTotal definition, CellValue value) {
}
