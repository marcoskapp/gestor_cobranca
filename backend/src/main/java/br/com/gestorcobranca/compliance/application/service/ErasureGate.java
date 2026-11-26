package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Origin;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Portão único da eliminação do titular (SEG-20261124-D01h, decisão 1481): a única classe que chama
 * {@link DebtorUseCases#anonymize} ({@code ArchitectureTest}). Na transação do chamador, o debt trava o devedor,
 * confere os títulos em cobrança e anonimiza; em seguida abre os avisos aos credores (LGPD art. 18 §6º). Se algo
 * falhar, nada fica: nem a anonimização sem aviso, nem o aviso sem anonimização.
 * <p>
 * É o ponto em que entram, nas fatias seguintes, as retenções que impedem a eliminação (pendência jurídica).
 */
@Component
class ErasureGate {

    private final DebtorUseCases debtors;
    private final CreditorErasureNoticeService notices;

    ErasureGate(DebtorUseCases debtors, CreditorErasureNoticeService notices) {
        this.debtors = debtors;
        this.notices = notices;
    }

    /**
     * @param origin   o que levou à eliminação (solicitação do titular ou retenção)
     * @param originId id da solicitação ou da execução de retenção
     * @param reason   motivo gravado na auditoria do debt
     * @return avisos abertos (um por credor com títulos do titular)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<CreditorErasureNotice> erase(UUID debtorId, Origin origin, UUID originId, String reason) {
        DebtorView erased = debtors.anonymize(debtorId, reason);
        return notices.openFor(debtorId, origin, originId, erased.anonymizedAt());
    }
}
