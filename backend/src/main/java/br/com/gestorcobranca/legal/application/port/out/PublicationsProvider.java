package br.com.gestorcobranca.legal.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Captura de publicações e intimações em diários oficiais (fornecedor especializado). Cada
 * publicação capturada vira andamento {@code PUBLICATION} do processo; o prazo decorrente é
 * cadastrado pelo responsável (a data de disponibilização vai como início em
 * {@code DJE_AVAILABILITY}).
 */
public interface PublicationsProvider extends LegalProvider {

    List<CapturedPublication> fetch(PublicationQuery query);

    record PublicationQuery(Set<String> cnjNumbers, LocalDate from, LocalDate to) {
    }

    record CapturedPublication(String externalId, String cnjNumber, LocalDate availableOn, String content) {
    }
}
