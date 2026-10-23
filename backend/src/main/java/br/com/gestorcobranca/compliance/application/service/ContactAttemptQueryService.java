package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ContactAttemptQueries;
import br.com.gestorcobranca.compliance.domain.model.ContactAttempt;
import br.com.gestorcobranca.compliance.domain.repository.ContactAttemptRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
class ContactAttemptQueryService implements ContactAttemptQueries {

    private final ContactAttemptRepository attempts;
    private final DebtorUseCases debtors;
    private final AccessScope accessScope;

    ContactAttemptQueryService(ContactAttemptRepository attempts, DebtorUseCases debtors, AccessScope accessScope) {
        this.attempts = attempts;
        this.debtors = debtors;
        this.accessScope = accessScope;
    }

    @Override
    public PageResponse<AttemptView> listByDebtor(UUID debtorId, PageQuery page) {
        debtors.get(debtorId);
        return PageResponse.of(attempts.findByDebtor(debtorId,
                accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null), page.toPageable()),
                ContactAttemptQueryService::view);
    }

    static AttemptView view(ContactAttempt a) {
        return new AttemptView(a.getId(), a.getDebtorId(), a.getCreditorId(), a.getChannel().name(), a.getAttemptedAt(),
                a.getOrigin().name(), a.getReferenceType(), a.getReferenceId(), a.getCreatedBy());
    }
}
