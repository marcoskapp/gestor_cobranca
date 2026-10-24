package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Página de recebimentos com o lançamento do razão de cada um. */
@Component
class ReceiptPages {

    private final CommissionEntryRepository entries;
    private final SettlementViews views;

    ReceiptPages(CommissionEntryRepository entries, SettlementViews views) {
        this.entries = entries;
        this.views = views;
    }

    PageResponse<ReceiptView> of(Page<Receipt> found) {
        Map<UUID, CommissionEntry> byReceipt = entries.findByReceiptIdIn(found.getContent().stream().map(Receipt::getId)
                .toList()).stream().collect(Collectors.toMap(CommissionEntry::getReceiptId, Function.identity()));
        return PageResponse.of(new PageImpl<>(found.getContent().stream()
                .map(r -> views.receipt(r, byReceipt.get(r.getId()))).toList(), found.getPageable(),
                found.getTotalElements()));
    }
}
