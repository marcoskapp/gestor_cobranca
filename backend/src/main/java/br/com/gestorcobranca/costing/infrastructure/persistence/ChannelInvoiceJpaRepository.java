package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.domain.model.ChannelInvoice;
import br.com.gestorcobranca.costing.domain.repository.ChannelInvoiceRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ChannelInvoiceJpaRepository extends JpaRepository<ChannelInvoice, UUID>, ChannelInvoiceRepository {
}
