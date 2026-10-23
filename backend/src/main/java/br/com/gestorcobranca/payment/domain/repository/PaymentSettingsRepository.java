package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.PaymentSettings;

import java.util.Optional;

public interface PaymentSettingsRepository {

    PaymentSettings save(PaymentSettings settings);

    Optional<PaymentSettings> findFirstByOrderByCreatedAtAsc();
}
