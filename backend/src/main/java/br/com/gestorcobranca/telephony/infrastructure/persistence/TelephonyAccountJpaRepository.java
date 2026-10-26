package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface TelephonyAccountJpaRepository extends JpaRepository<TelephonyAccount, UUID>, TelephonyAccountRepository {
}
