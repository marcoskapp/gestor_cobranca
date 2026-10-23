package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PaymentReversalJpaRepository extends JpaRepository<PaymentReversal, UUID>, PaymentReversalRepository {
}
