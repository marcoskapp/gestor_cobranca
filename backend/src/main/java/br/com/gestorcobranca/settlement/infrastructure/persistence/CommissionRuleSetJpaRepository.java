package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.repository.CommissionRuleSetRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface CommissionRuleSetJpaRepository extends JpaRepository<CommissionRuleSet, UUID>, CommissionRuleSetRepository {

    List<CommissionRuleSet> findByCreditorIdInOrderByRuleVersionDesc(Collection<UUID> creditorIds);

    List<CommissionRuleSet> findAllByOrderByRuleVersionDesc();

    @Override
    default List<CommissionRuleSet> findVisible(Collection<UUID> creditorIds) {
        if (creditorIds == null) {
            return findAllByOrderByRuleVersionDesc();
        }
        return creditorIds.isEmpty() ? List.of() : findByCreditorIdInOrderByRuleVersionDesc(creditorIds);
    }
}
