package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeDebtorNotice;
import br.com.gestorcobranca.collection.domain.repository.DisputeDebtorNoticeRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeDebtorNoticeJpaRepository extends JpaRepository<DisputeDebtorNotice, UUID>, DisputeDebtorNoticeRepository {
}
