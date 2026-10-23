package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LegalExpense;
import br.com.gestorcobranca.legal.domain.repository.LegalExpenseRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LegalExpenseJpaRepository extends JpaRepository<LegalExpense, UUID>, LegalExpenseRepository {

    @Override
    Optional<LegalExpense> findById(UUID id);
}
