package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.AgreementRecord;
import br.com.gestorcobranca.legal.domain.repository.AgreementRecordRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AgreementRecordJpaRepository extends JpaRepository<AgreementRecord, UUID>, AgreementRecordRepository {
}
