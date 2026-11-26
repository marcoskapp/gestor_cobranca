package br.com.gestorcobranca.creditorportal.application.service;

import br.com.gestorcobranca.creditorportal.application.port.in.CreditorStatementUseCases;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorStepUp;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorDailyPoint;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorFileContent;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorOpenPeriod;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorStatementDetail;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorStatementSummary;
import br.com.gestorcobranca.settlement.application.port.in.creditor.CreditorSettlementUseCases.CreditorTotals;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.StepUpPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Prestação de contas no portal do credor: traduz para a lista de campos permitidos e orquestra o aceite com MFA. */
@Service
@Transactional
class CreditorStatementService implements CreditorStatementUseCases {

    private final CreditorSettlementUseCases settlement;
    private final CreditorStepUp stepUp;

    CreditorStatementService(CreditorSettlementUseCases settlement, CreditorStepUp stepUp) {
        this.settlement = settlement;
        this.stepUp = stepUp;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatementItem> statements() {
        return settlement.statements().stream().map(CreditorStatementService::item).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StatementDetail statement(UUID statementId) {
        return detail(settlement.statement(statementId));
    }

    @Override
    @Transactional(readOnly = true)
    public OpenPeriod openPeriod() {
        CreditorOpenPeriod p = settlement.openPeriod();
        return new OpenPeriod(p.periodStart(), p.asOf(), p.expectedPeriodEnd(), p.recovered(), p.netTransfer(),
                p.direction(), p.calculatedAt(), daily(p.daily()), daily(p.previousDaily()));
    }

    @Override
    @Transactional(readOnly = true)
    public FileContent download(UUID statementId, UUID fileId) {
        CreditorFileContent f = settlement.download(statementId, fileId);
        return new FileContent(f.fileName(), f.contentType(), f.size(), f.sha256(), f.content());
    }

    @Override
    public StatementDetail acknowledge(UUID statementId, AcknowledgeCommand c) {
        Guard.require(c != null && c.code() != null && c.code().matches("\\d{6}"), "statement.mfa_required",
                "Informe o código de 6 dígitos do autenticador");
        // sem efeito, antes do código: perfil que não responde (ou demonstrativo que não é dele) não gasta tentativa
        settlement.requireCanRespond(statementId);
        // confere o código antes de qualquer efeito; se o aceite falhar depois, a transação inteira volta
        String proof = stepUp.verify(c.code(), StepUpPurpose.STATEMENT_ACKNOWLEDGE, statementId.toString());
        return detail(settlement.acknowledge(statementId,
                new CreditorResponseCommand(c.note(), c.fileSha256(), c.clientIp(), proof)));
    }

    @Override
    public StatementDetail contest(UUID statementId, ContestCommand c) {
        Guard.require(c != null && c.reason() != null && !c.reason().isBlank(), "statement.contest_reason_required",
                "Informe o motivo da contestação");
        return detail(settlement.contest(statementId,
                new CreditorResponseCommand(c.reason(), List.of(), c.clientIp(), null)));
    }

    static StatementItem item(CreditorStatementSummary s) {
        return new StatementItem(s.id(), s.sequenceNumber(), s.periodStart(), s.periodEnd(), s.status(), s.recovered(),
                s.netTransfer(), s.direction(), s.sentAt(), s.respondedAt(), s.responseDueOn(), s.responseOverdue(),
                s.transferred(), s.outstanding());
    }

    private static List<DailyPoint> daily(List<CreditorDailyPoint> points) {
        return points.stream().map(d -> new DailyPoint(d.day(), d.date(), d.recovered(), d.netTransfer())).toList();
    }

    private static StatementDetail detail(CreditorStatementDetail d) {
        CreditorTotals t = d.totals();
        return new StatementDetail(item(d.summary()),
                new Totals(t.receivedByAgency(), t.receivedByCreditor(), t.reversed(), t.recovered(),
                        t.agencyHonorarium(), t.commission(), t.fixedFees(), t.expenses(), t.adjustments(),
                        t.netTransfer()),
                d.files().stream().map(f -> new FileItem(f.id(), f.format(), f.fileName(), f.size(), f.sha256(),
                        f.createdAt())).toList(),
                d.transferred(), d.outstanding(), d.responseNote(), d.respondedInPortal());
    }
}
