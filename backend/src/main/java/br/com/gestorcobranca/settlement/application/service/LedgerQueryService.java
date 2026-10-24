package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries;
import br.com.gestorcobranca.settlement.domain.model.RatingStatus;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class LedgerQueryService implements CommissionLedgerQueries {

    static final String RESOURCE = "settlement_receipt";

    private final ReceiptRepository receipts;
    private final CommissionEntryRepository entries;
    private final RatingService rating;
    private final ReceiptPages pages;
    private final SettlementViews views;
    private final SettlementAccess access;
    private final AccessScope accessScope;

    LedgerQueryService(ReceiptRepository receipts, CommissionEntryRepository entries, RatingService rating,
                       ReceiptPages pages, SettlementViews views, SettlementAccess access, AccessScope accessScope) {
        this.receipts = receipts;
        this.entries = entries;
        this.rating = rating;
        this.pages = pages;
        this.views = views;
        this.access = access;
        this.accessScope = accessScope;
    }

    @Override
    public PageResponse<ReceiptView> receipts(ReceiptFilter f, PageQuery page) {
        access.requireAny(Permission.COMMISSION_VIEW, Permission.SETTLEMENT_VIEW);
        if (f.creditorId() != null) {
            accessScope.requireCreditor(f.creditorId());
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        RatingStatus status = f.ratingStatus() == null ? null
                : SettlementAccess.parse(RatingStatus.class, f.ratingStatus(), "receipt.rating_status_invalid");
        Page<Receipt> found = receipts.search(allowed, f.creditorId(), f.agreementId(), f.from(), f.to(), status,
                f.inStatement(), page.toPageable(Sort.by(Sort.Direction.DESC, "competenceDate").and(Sort.by("id"))));
        return pages.of(found);
    }

    @Override
    public ReceiptDetailView receipt(UUID receiptId) {
        access.requireAny(Permission.COMMISSION_VIEW, Permission.SETTLEMENT_VIEW);
        Receipt r = receipts.findById(receiptId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, receiptId));
        accessScope.requireVisible(RESOURCE, receiptId, r.getCreditorId());
        List<ReceiptView> reversals = receipts.findByReversesReceiptId(r.getId()).stream()
                .map(rev -> views.receipt(rev, entries.findByReceiptId(rev.getId()).orElse(null))).toList();
        return new ReceiptDetailView(views.receipt(r, entries.findByReceiptId(r.getId()).orElse(null)), views.detail(r),
                reversals);
    }

    @Override
    @Transactional
    public int ratePending(UUID creditorId) {
        accessScope.requirePermission(Permission.COMMISSION_MANAGE);
        accessScope.requireCreditor(creditorId);
        return rating.ratePending(creditorId);
    }
}
