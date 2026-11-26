package br.com.gestorcobranca.creditorportal.application.service;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorActivityPortalUseCases;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorActivityQueries;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorActivityQueries.CreditorActivityPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "Minha atividade" no portal do credor: traduz a página do identity para a lista de campos permitidos. */
@Service
@Transactional(readOnly = true)
class CreditorActivityPortalService implements CreditorActivityPortalUseCases {

    private final CreditorActivityQueries activity;
    private final CreditorPortalMetrics metrics;

    CreditorActivityPortalService(CreditorActivityQueries activity, CreditorPortalMetrics metrics) {
        this.activity = activity;
        this.metrics = metrics;
    }

    @Override
    public ActivityPage activity(boolean team, String before, int size) {
        metrics.activity(team ? "team" : "own");
        CreditorActivityPage page = activity.activity(team, before, size);
        return new ActivityPage(team ? "TEAM" : "OWN", page.items().stream()
                .map(i -> new ActivityItem(i.occurredAt(), i.action(), i.mine(), i.personName(), i.referenceType(),
                        i.referenceId())).toList(),
                page.nextCursor(), CreditorActivityQueries.WINDOW_DAYS);
    }
}
