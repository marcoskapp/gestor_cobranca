package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases;
import br.com.gestorcobranca.incentive.domain.service.BusinessDays;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

/** Dias úteis da assessoria: segunda a sexta menos os feriados do calendário do compliance. */
@Component
class HolidayCalendar {

    private final HolidayUseCases holidays;

    HolidayCalendar(HolidayUseCases holidays) {
        this.holidays = holidays;
    }

    LocalDate plusBusinessDays(LocalDate start, int businessDays) {
        return BusinessDays.plus(start, businessDays, holidaysBetween(start, BusinessDays.horizon(start, businessDays)));
    }

    private Set<LocalDate> holidaysBetween(LocalDate from, LocalDate to) {
        return holidays.list(from, to).stream().map(HolidayUseCases.HolidayView::date).collect(Collectors.toSet());
    }
}
