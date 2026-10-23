package br.com.gestorcobranca.compliance.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Calendário de feriados da assessoria, usado pelas políticas de contato com bloqueio em feriados. */
public interface HolidayUseCases {

    /** Data já cadastrada → conflito {@code holiday.duplicate}. Exige acesso a todos os credores. */
    HolidayView add(LocalDate date, String name);

    void remove(UUID holidayId);

    List<HolidayView> list(LocalDate from, LocalDate to);

    record HolidayView(UUID id, LocalDate date, String name) {
    }
}
