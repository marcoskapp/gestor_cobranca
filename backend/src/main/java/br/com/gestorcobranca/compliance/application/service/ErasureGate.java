package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Origin;
import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.Anonymization;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.security.CurrentActor;
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
    private final BlindIndex blindIndex;
    private final CurrentActor currentActor;

    ErasureGate(DebtorUseCases debtors, CreditorErasureNoticeService notices, BlindIndex blindIndex,
                CurrentActor currentActor) {
        this.debtors = debtors;
        this.notices = notices;
        this.blindIndex = blindIndex;
        this.currentActor = currentActor;
    }

    /**
     * @param notices     avisos abertos (um por credor com títulos do titular)
     * @param subjectHash índice do documento eliminado com a finalidade do pedido (decisão 1471), só na eliminação a
     *                    pedido do titular; nulo na retenção (sem pedido, resíduo registrado) ou sem documento
     */
    record Erasure(List<CreditorErasureNotice> notices, String subjectHash) {
        @Override
        public String toString() {
            return "Erasure[notices=" + notices.size() + "]";
        }
    }

    /**
     * @param origin   o que levou à eliminação (solicitação do titular ou retenção)
     * @param originId id da solicitação ou da execução de retenção
     * @param reason   motivo gravado na auditoria do debt
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Erasure erase(UUID debtorId, Origin origin, UUID originId, String reason) {
        Anonymization erased = debtors.anonymize(debtorId, reason);
        // só o índice sai daqui; o documento nunca é gravado no compliance (minimização, decisão 1499)
        String subjectHash = origin != Origin.DATA_SUBJECT_REQUEST ? null : erased.erasedDocument()
                .map(d -> blindIndex.of(currentActor.tenantId(), DataSubjectRequest.DOCUMENT_HASH_PURPOSE, d.number()))
                .orElse(null);
        return new Erasure(notices.openFor(debtorId, origin, originId, erased.debtor().anonymizedAt()), subjectHash);
    }
}
