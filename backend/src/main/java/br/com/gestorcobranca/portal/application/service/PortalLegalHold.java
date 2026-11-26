package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.SelfServiceHold;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Decide se a dívida indisponível no portal pode mostrar o aviso próprio da retenção jurídica ({@code LEGAL_HOLD})
 * em vez do genérico ({@code NOT_ELIGIBLE}). Só quando a retenção jurídica é o <strong>único</strong> motivo:
 * qualquer outro (contestação, recuperação judicial ou falência, carteira não operável, marcador de vulnerabilidade
 * ou superendividamento) mantém o genérico, para que a diferença entre os avisos nunca revele outra situação do
 * devedor (minimização; decisão 1642, risco aceito SEG-20261126-PA04). O collection reavalia o caso na hora
 * ({@link CaseUseCases#selfServiceHold}); o portal soma o que é dele saber: contestação que cobre só parte dos títulos
 * e marcador de vulnerabilidade ativo (qualquer um, com ou sem salvaguarda ligada). Na dúvida, genérico.
 */
@Component
class PortalLegalHold {

    private final CaseUseCases cases;
    private final VulnerabilityQueries vulnerability;

    PortalLegalHold(CaseUseCases cases, VulnerabilityQueries vulnerability) {
        this.cases = cases;
        this.vulnerability = vulnerability;
    }

    /** @param titles títulos da carteira exibidos no portal (em aberto) */
    boolean onlyLegalHold(UUID debtorId, UUID portfolioId, List<TitleView> titles) {
        return cases.selfServiceHold(debtorId, portfolioId) == SelfServiceHold.LEGAL_ONLY
                && titles.stream().noneMatch(TitleView::disputeBlocked)
                && !vulnerability.isVulnerable(debtorId);
    }
}
