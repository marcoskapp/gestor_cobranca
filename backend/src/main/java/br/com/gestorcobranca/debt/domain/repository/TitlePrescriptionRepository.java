package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.TitlePrescription;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TitlePrescriptionRepository {

    TitlePrescription save(TitlePrescription prescription);

    void delete(TitlePrescription prescription);

    List<TitlePrescription> findByTitleId(UUID titleId);

    Optional<TitlePrescription> findByTitleIdAndClaim(UUID titleId, PrescriptionClaim claim);

    /**
     * Prescrições de títulos em cobrança (abertos ou suspensos) cuja data estimada está entre
     * {@code today} e {@code today + alert_days} da regra aplicada e ainda não alertadas para essa
     * data, por data estimada.
     */
    List<TitlePrescription> findAlertCandidates(LocalDate today, int limit);

    /**
     * Contratos com título em cobrança sem a prescrição de uma pretensão aplicável (regra da
     * natureza; EXECUTION só para título executivo): títulos anteriores às regras, regras
     * semeadas sem recálculo ou recálculo interrompido. Ordenados por id.
     */
    List<UUID> findContractIdsMissingPrescription(int limit);
}
