package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LegalExpense;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalExpenseRepository {

    LegalExpense save(LegalExpense expense);

    Optional<LegalExpense> findById(UUID id);

    List<LegalExpense> findByMatterIdOrderByIncurredOnDescCreatedAtDesc(UUID matterId);
}
