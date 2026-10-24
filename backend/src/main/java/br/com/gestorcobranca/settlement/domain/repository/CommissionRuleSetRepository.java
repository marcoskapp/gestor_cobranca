package br.com.gestorcobranca.settlement.domain.repository;

import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.model.RuleSetStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionRuleSetRepository {

    CommissionRuleSet save(CommissionRuleSet ruleSet);

    Optional<CommissionRuleSet> findById(UUID id);

    List<CommissionRuleSet> findByContractIdOrderByRuleVersionDesc(UUID contractId);

    List<CommissionRuleSet> findByCreditorIdOrderByRuleVersionDesc(UUID creditorId);

    /** @param creditorIds credores visíveis; {@code null} = todos */
    List<CommissionRuleSet> findVisible(Collection<UUID> creditorIds);

    Optional<CommissionRuleSet> findByContractIdAndStatus(UUID contractId, RuleSetStatus status);

    /** Versão publicada do contrato vigente na data. */
    default Optional<CommissionRuleSet> findEffective(UUID contractId, LocalDate date) {
        return findByContractIdOrderByRuleVersionDesc(contractId).stream().filter(r -> r.covers(date)).findFirst();
    }

    default int nextVersion(UUID contractId) {
        return findByContractIdOrderByRuleVersionDesc(contractId).stream().mapToInt(CommissionRuleSet::getRuleVersion)
                .max().orElse(0) + 1;
    }
}
