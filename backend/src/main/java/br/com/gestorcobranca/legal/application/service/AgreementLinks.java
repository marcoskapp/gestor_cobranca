package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.AgreementRecord;
import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.DeadlineKind;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.legal.domain.repository.AgreementRecordRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Vínculo de acordos do negotiation (por id) ao caso jurídico. O acordo judicial cria o prazo
 * para protocolar o acordo para homologação (parâmetro da assessoria, em dias úteis).
 */
@Component
class AgreementLinks {

    private final AgreementRecordRepository records;
    private final DeadlineRecorder deadlines;
    private final MatterRecorder matterRecorder;
    private final LegalSettingsProvider settings;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    AgreementLinks(AgreementRecordRepository records, DeadlineRecorder deadlines, MatterRecorder matterRecorder,
                   LegalSettingsProvider settings, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                   Clock clock) {
        this.records = records;
        this.deadlines = deadlines;
        this.matterRecorder = matterRecorder;
        this.settings = settings;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    AgreementRecord recordOf(UUID agreementId, LegalMatter matter) {
        return records.findByAgreementId(agreementId).orElseGet(() -> new AgreementRecord(agreementId,
                matter.getCollectionCaseId(), matter.getDebtorId(), matter.getCreditorId(), matter.getPortfolioId(), null));
    }

    /** Vincula o acordo ao caso jurídico; no acordo judicial, cria o prazo de homologação. */
    void link(LegalMatter matter, AgreementRecord record, boolean judicial, String cause) {
        record.linkMatter(matter.getId(), judicial);
        records.save(record);
        auditTrail.record(AuditEntry.of("legal_matter.agreement_linked", MatterRecorder.ENTITY, matter.getId())
                .creditor(matter.getCreditorId())
                .meta("agreementId", record.getAgreementId())
                .meta("judicial", judicial)
                .meta("cause", cause));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_AGREEMENT_LINKED, matter)
                .attr("agreementId", record.getAgreementId()).attr("judicial", judicial).attr("stage", matter.getStage())
                .attr("cause", cause));
        if (judicial) {
            deadlines.create(matter, new DeadlineRecorder.Spec(DeadlineKind.FILING,
                    "Protocolar o acordo para homologação judicial", null, null, null, null, LegalViews.today(clock),
                    StartEvent.INTIMATION, settings.current().homologationDeadlineBusinessDays(),
                    CountingMode.BUSINESS_DAYS, null, null), "agreement");
        }
    }
}
