package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.PaymentSettings;
import br.com.gestorcobranca.payment.domain.repository.PaymentSettingsRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PaymentSettingsJpaRepository extends JpaRepository<PaymentSettings, UUID>, PaymentSettingsRepository {
}
