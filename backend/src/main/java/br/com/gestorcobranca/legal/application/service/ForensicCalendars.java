package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases;
import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.legal.domain.repository.CourtSuspensionRepository;
import br.com.gestorcobranca.legal.domain.service.DeadlineCalculator;
import br.com.gestorcobranca.legal.domain.service.DeadlineCalculator.ForensicCalendar;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calendário forense de um período e tribunal: feriados da assessoria (porta pública
 * {@link HolidayUseCases} do compliance), suspensões forenses cadastradas no jurídico que alcançam
 * o tribunal (ou todos) e o recesso de fim de ano conforme o parâmetro da assessoria.
 */
@Component
class ForensicCalendars {

    private final HolidayUseCases holidays;
    private final CourtSuspensionRepository suspensions;
    private final LegalSettingsProvider settings;

    ForensicCalendars(HolidayUseCases holidays, CourtSuspensionRepository suspensions, LegalSettingsProvider settings) {
        this.holidays = holidays;
        this.suspensions = suspensions;
        this.settings = settings;
    }

    ForensicCalendar between(LocalDate from, LocalDate to, String courtCode) {
        Set<LocalDate> holidayDates = holidays.list(from, to).stream().map(HolidayUseCases.HolidayView::date)
                .collect(Collectors.toSet());
        List<DeadlineCalculator.Suspension> periods = suspensions
                .findByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(to, from).stream()
                .filter(s -> s.appliesTo(courtCode))
                .map(s -> new DeadlineCalculator.Suspension(s.getStartDate(), s.getEndDate()))
                .toList();
        return new ForensicCalendar(holidayDates, periods, settings.current().yearEndRecess());
    }

    LocalDate dueDate(LocalDate start, StartEvent event, Integer term, CountingMode mode, LocalDate fixedDate,
                      String courtCode) {
        LocalDate from = mode == CountingMode.FIXED_DATE ? fixedDate : start;
        if (from == null) {
            return DeadlineCalculator.dueDate(start, event, term == null ? 0 : term, mode, fixedDate,
                    new ForensicCalendar(Set.of(), List.of(), false));
        }
        LocalDate to = DeadlineCalculator.horizon(from, term == null ? 0 : term, mode, fixedDate);
        return DeadlineCalculator.dueDate(start, event, term == null ? 0 : term, mode, fixedDate,
                between(from, to, courtCode));
    }

    /** Vencimento recalculado de um prazo pendente com o calendário atual. */
    LocalDate recompute(LegalDeadline d) {
        return dueDate(d.getStartDate(), d.getStartEvent(), d.getTermDays(), d.getCountingMode(), d.getFixedDate(),
                d.getCourtCode());
    }

    /** Dias úteis restantes (negativo = vencido). */
    int remaining(LocalDate today, LocalDate due, String courtCode) {
        if (due.isBefore(today)) {
            return -1;
        }
        return DeadlineCalculator.businessDaysRemaining(today, due, between(today, due.plusDays(1), courtCode));
    }
}
