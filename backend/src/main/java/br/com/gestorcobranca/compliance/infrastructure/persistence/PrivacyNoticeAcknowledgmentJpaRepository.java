package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.PrivacyNoticeAcknowledgment;
import br.com.gestorcobranca.compliance.domain.repository.PrivacyNoticeAcknowledgmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PrivacyNoticeAcknowledgmentJpaRepository extends JpaRepository<PrivacyNoticeAcknowledgment, UUID>,
        PrivacyNoticeAcknowledgmentRepository {
}
