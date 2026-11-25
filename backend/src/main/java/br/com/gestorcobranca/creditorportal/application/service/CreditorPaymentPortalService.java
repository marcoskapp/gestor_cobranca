package br.com.gestorcobranca.creditorportal.application.service;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorPaymentPortalUseCases;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorStepUp;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases.VerifiedEvidence;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.StepUpPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Informe de pagamento no portal do credor. A verificação do comprovante (malware e conteúdo ativo) roda <b>antes</b> e
 * <b>fora</b> da transação (decisão 1437); depois, numa transação só: conferência sem efeito (permissão, títulos,
 * limite), nova verificação do MFA (comprovante de uso único para este informe) e a gravação — se algo falhar, nada fica.
 */
@Service
class CreditorPaymentPortalService implements CreditorPaymentPortalUseCases {

    private final CreditorPaymentUseCases payments;
    private final CreditorStepUp stepUp;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    CreditorPaymentPortalService(CreditorPaymentUseCases payments, CreditorStepUp stepUp,
                                 PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.stepUp = stepUp;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public List<TitleMatch> findTitles(String number) {
        return readOnly.execute(status -> payments.findTitles(number).stream()
                .map(t -> new TitleMatch(t.number(), t.installmentNumber(), t.dueDate(), t.openBalance(),
                        t.underAgreement())).toList());
    }

    @Override
    public ReportedPayment report(ReportCommand c, Evidence evidence, String idempotencyKey) {
        Guard.require(c != null && c.code() != null && c.code().matches("\\d{6}"), "payment.mfa_required",
                "Informe o código de 6 dígitos do autenticador");
        CreditorPaymentUseCases.ReportCommand command = new CreditorPaymentUseCases.ReportCommand(c.titleNumbers(),
                c.amount(), c.paidOn(), c.method(), c.reference(), c.note());
        // fora de transação: nenhuma conexão nem trava presa durante a verificação do arquivo (SEG-H06)
        VerifiedEvidence verified = payments.screen(evidence == null ? null : new CreditorPaymentUseCases.EvidenceUpload(
                evidence.fileName(), evidence.contentType(), evidence.size(), evidence.content()));
        return transaction.execute(status -> {
            // sem efeito, antes do código: perfil sem permissão ou título que não dá não gasta tentativa
            payments.requireCanReport(command, verified, idempotencyKey);
            String proof = stepUp.verify(c.code(), StepUpPurpose.PAYMENT_REPORT,
                    CreditorPaymentUseCases.subjectRef(command, verified.sha256()));
            return item(payments.report(command, verified, idempotencyKey, proof));
        });
    }

    @Override
    public List<ReportedPayment> reported() {
        return readOnly.execute(status -> payments.reported().stream().map(CreditorPaymentPortalService::item).toList());
    }

    private static ReportedPayment item(CreditorPaymentUseCases.ReportedPayment p) {
        return new ReportedPayment(p.id(), p.status(), p.amount(), p.paidOn(), p.method(), p.titleNumbers(),
                p.reference(), p.informedAt(), p.decidedAt(), p.rejectionReason());
    }
}
