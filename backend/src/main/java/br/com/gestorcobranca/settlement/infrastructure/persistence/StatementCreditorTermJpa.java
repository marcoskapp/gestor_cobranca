package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.StatementCreditorTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Termo do portal do credor: só inclusão (gatilho e privilégios no banco). */
interface StatementCreditorTermJpa extends JpaRepository<StatementCreditorTerm, UUID> {
}
