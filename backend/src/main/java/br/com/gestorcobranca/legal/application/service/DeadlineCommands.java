package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineCommand;
import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.DeadlineKind;
import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.legal.domain.repository.ExtrajudicialMeasureRepository;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Conversão e validação do comando de prazo (processo e medida do próprio caso jurídico). */
@Component
class DeadlineCommands {

    private final LawsuitRepository lawsuits;
    private final ExtrajudicialMeasureRepository measures;
    private final UserDirectory users;

    DeadlineCommands(LawsuitRepository lawsuits, ExtrajudicialMeasureRepository measures, UserDirectory users) {
        this.lawsuits = lawsuits;
        this.measures = measures;
        this.users = users;
    }

    /**
     * @param lawsuit   processo já carregado (andamento) ou nulo para usar {@code command.lawsuitId()}
     * @param movementId andamento que originou o prazo
     */
    DeadlineRecorder.Spec spec(LegalMatter matter, DeadlineCommand command, Lawsuit lawsuit, UUID movementId) {
        Lawsuit target = lawsuit;
        if (target == null && command.lawsuitId() != null) {
            target = lawsuits.findById(command.lawsuitId()).filter(l -> l.getMatterId().equals(matter.getId()))
                    .orElseThrow(() -> new BusinessRuleException("legal_deadline.lawsuit_invalid",
                            "Processo não pertence ao caso jurídico"));
        }
        if (command.measureId() != null) {
            measures.findById(command.measureId()).filter(x -> x.getMatterId().equals(matter.getId()))
                    .orElseThrow(() -> new BusinessRuleException("legal_deadline.measure_invalid",
                            "Medida não pertence ao caso jurídico"));
        }
        if (command.responsibleUserId() != null) {
            users.find(command.responsibleUserId()).filter(UserDirectory.UserSummary::active)
                    .filter(u -> u.canAccessCreditor(matter.getCreditorId()))
                    .orElseThrow(() -> new BusinessRuleException("legal_deadline.responsible_invalid",
                            "Responsável inexistente, inativo ou sem acesso ao credor"));
        }
        CountingMode mode = command.countingMode() == null || command.countingMode().isBlank()
                ? CountingMode.BUSINESS_DAYS
                : LegalViews.parse(CountingMode.class, command.countingMode(), "legal_deadline.mode_invalid", "Contagem");
        StartEvent event = command.startEvent() == null || command.startEvent().isBlank() ? StartEvent.INTIMATION
                : LegalViews.parse(StartEvent.class, command.startEvent(), "legal_deadline.start_event_invalid",
                "Fato inicial");
        return new DeadlineRecorder.Spec(
                LegalViews.parse(DeadlineKind.class, command.kind(), "legal_deadline.kind_invalid", "Natureza do prazo"),
                command.description(), target == null ? null : target.getId(), movementId, command.measureId(),
                target == null ? null : target.getCourtCode(), command.startDate(), event, command.termDays(), mode,
                command.fixedDate(), command.responsibleUserId());
    }
}
