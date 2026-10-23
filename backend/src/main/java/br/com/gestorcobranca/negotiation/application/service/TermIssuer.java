package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer;
import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer.TermDocument;
import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer.TermInstallment;
import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer.TermTitle;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Evaluation;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.BreakRule;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emite o termo de acordo (PDF) das condições aceitas e o guarda no {@code FileStorage}
 * ({@code AGREEMENT_TERM}, chave prefixada pelo tenant, SHA-256). O armazenamento não participa da
 * transação do banco: se a transação for desfeita, o arquivo é removido (sem dado pessoal órfão).
 */
@Component
class TermIssuer {

    private static final Logger LOG = LoggerFactory.getLogger(TermIssuer.class);

    private final AgreementTermRenderer renderer;
    private final FileStorage fileStorage;
    private final CreditorUseCases creditors;
    private final DebtorUseCases debtors;
    private final DebtTitles debtTitles;
    private final NegotiationMetrics metrics;
    private final Clock clock;

    TermIssuer(AgreementTermRenderer renderer, FileStorage fileStorage, CreditorUseCases creditors,
               DebtorUseCases debtors, DebtTitles debtTitles, NegotiationMetrics metrics, Clock clock) {
        this.renderer = renderer;
        this.fileStorage = fileStorage;
        this.creditors = creditors;
        this.debtors = debtors;
        this.debtTitles = debtTitles;
        this.metrics = metrics;
        this.clock = clock;
    }

    StoredFile issue(Proposal proposal, Evaluation evaluation, BreakRule breakRule, String channel) {
        Instant started = Instant.now(clock);
        CreditorUseCases.CreditorView creditor = creditors.get(proposal.getCreditorId());
        DebtorUseCases.DebtorView debtor = debtors.get(proposal.getDebtorId());
        Map<UUID, TitleView> titles = debtTitles.ofDebtor(proposal.getDebtorId(), proposal.getCreditorId());
        List<TermTitle> termTitles = evaluation.priced().titles().stream().map(t -> {
            TitleView title = titles.get(t.titleId());
            return new TermTitle(title == null ? t.titleId().toString() : title.externalId(),
                    title == null ? null : title.dueDate(), t.gross().total().amount());
        }).toList();
        var plan = evaluation.plan();
        TermDocument document = new TermDocument(proposal.getId(), proposal.getReplacedAgreementId(),
                creditor.tradeName() == null ? creditor.legalName() : creditor.tradeName(), debtor.name(),
                debtor.maskedDocument(), termTitles, evaluation.priced().gross().total().amount(),
                evaluation.priced().discount().total().amount(), plan.negotiated().amount(),
                plan.financingInterest().amount(), plan.total().amount(),
                plan.schedule().stream().map(p -> new TermInstallment(p.number(), p.dueDate(), p.amount().amount())).toList(),
                "O acordo é considerado quebrado quando " + breakRule.overdueInstallments()
                        + " parcela(s) estiverem em atraso há mais de " + breakRule.toleranceDays()
                        + " dia(s); nesse caso as parcelas restantes são canceladas e o saldo devedor volta a ser cobrado"
                        + " pelas condições originais, abatidos os pagamentos efetuados.",
                proposal.getPolicyVersion(), channel, Instant.now(clock));
        byte[] pdf = renderer.render(document);
        StoredFile stored = fileStorage.store(FileCategory.AGREEMENT_TERM, "termo-acordo-" + proposal.getId() + ".pdf",
                "application/pdf", new ByteArrayInputStream(pdf), pdf.length);
        deleteOnRollback(stored.key());
        metrics.termRendered(Duration.between(started, Instant.now(clock)));
        return stored;
    }


    private void deleteOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        fileStorage.delete(key);
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("storageKey", key).setCause(e)
                                .log("Termo gravado por transação desfeita não pôde ser removido");
                    }
                }
            }
        });
    }
}
