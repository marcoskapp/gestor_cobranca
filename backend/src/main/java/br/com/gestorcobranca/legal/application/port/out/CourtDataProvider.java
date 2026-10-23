package br.com.gestorcobranca.legal.application.port.out;

import java.time.LocalDate;
import java.util.List;

/**
 * Metadados e andamentos processuais (primeira opção: API pública do DataJud/CNJ). A captura é
 * idempotente pelo {@code externalId} de cada andamento.
 */
public interface CourtDataProvider extends LegalProvider {

    /**
     * @param cnjNumber 20 dígitos
     * @param courtCode {@code J.TR} (ex.: 8.26), para escolher o índice do tribunal
     * @param since     andamentos a partir desta data (nula = todos)
     */
    CourtCapture fetch(CourtQuery query);

    record CourtQuery(String cnjNumber, String courtCode, LocalDate since) {
    }

    /** @param found falso se o processo não foi localizado no provedor */
    record CourtCapture(boolean found, String courtName, String courtUnit, String actionClass,
                        List<CapturedMovement> movements) {

        public CourtCapture {
            movements = movements == null ? List.of() : List.copyOf(movements);
        }
    }

    /**
     * @param externalId identificador estável do andamento no provedor
     * @param type       natureza mapeada pelo adaptador (nome de {@code MovementType}; OTHER se não mapeada)
     */
    record CapturedMovement(String externalId, LocalDate occurredOn, String type, String description) {
    }
}
