package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.CreditorTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

interface CreditorTransferJpa extends JpaRepository<CreditorTransfer, UUID>, JpaSpecificationExecutor<CreditorTransfer> {
    List<CreditorTransfer> findByStatementIdOrderByCreatedAtAsc(UUID statementId);
}
