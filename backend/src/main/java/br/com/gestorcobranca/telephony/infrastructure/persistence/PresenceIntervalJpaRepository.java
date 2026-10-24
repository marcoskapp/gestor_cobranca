package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.PresenceInterval;
import br.com.gestorcobranca.telephony.domain.repository.PresenceIntervalRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PresenceIntervalJpaRepository extends JpaRepository<PresenceInterval, UUID>, PresenceIntervalRepository {
}
