package br.com.gestorcobranca.incentive.application.port.out;

import br.com.gestorcobranca.incentive.domain.model.tier.PersonSample;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Insumos da classificação por pessoa na janela (datas inclusive): monitoria ({@code quality_evaluation}, pela data
 * avaliada) e recuperado × esperado ({@code expected_recovery_fact}, pela data de referência da safra). Quem não tem
 * dado algum não aparece no mapa.
 */
public interface TierSampleReader {

    Map<UUID, PersonSample> samples(Collection<UUID> userIds, LocalDate from, LocalDate to);
}
