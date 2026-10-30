package br.com.gestorcobranca.publicapi.domain.repository;

import br.com.gestorcobranca.publicapi.domain.model.PricePlan;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PricePlanRepository {

    PricePlan save(PricePlan plan);

    Optional<PricePlan> findById(UUID id);

    List<PricePlan> findAllOrderByCodeAscPlanVersionDesc();

    int maxVersion(String code);

    /**
     * Versão publicada aplicável ao mês: a mais recente com vigência até {@code monthStart}, do credor ou, sem tabela
     * própria, a padrão da assessoria.
     */
    Optional<PricePlan> findApplicable(UUID creditorId, LocalDate monthStart);
}
