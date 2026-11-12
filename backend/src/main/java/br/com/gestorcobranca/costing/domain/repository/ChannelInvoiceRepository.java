package br.com.gestorcobranca.costing.domain.repository;

import br.com.gestorcobranca.costing.domain.model.ChannelInvoice;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelInvoiceRepository {

    ChannelInvoice save(ChannelInvoice invoice);

    ChannelInvoice saveAndFlush(ChannelInvoice invoice);

    Optional<ChannelInvoice> findById(UUID id);

    void delete(ChannelInvoice invoice);

    /** @param month primeiro dia do mês */
    List<ChannelInvoice> findAllByMonth(LocalDate month);

    /** @param month primeiro dia do mês */
    Optional<ChannelInvoice> findByMonthAndItem(LocalDate month, ChannelItem item);

    /** Meses entre {@code from} e {@code to} (primeiros dias), inclusive. */
    List<ChannelInvoice> findAllByMonthBetweenOrderByMonthDescItemAsc(LocalDate from, LocalDate to);
}
