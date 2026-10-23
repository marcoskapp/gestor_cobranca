package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases;
import br.com.gestorcobranca.compliance.domain.model.Holiday;
import br.com.gestorcobranca.compliance.domain.repository.HolidayRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
class HolidayService implements HolidayUseCases {

    private static final long MAX_RANGE_DAYS = 3 * 366;

    private final HolidayRepository holidays;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    HolidayService(HolidayRepository holidays, AccessScope accessScope, AuditTrail auditTrail) {
        this.holidays = holidays;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    public HolidayView add(LocalDate date, String name) {
        accessScope.requireAllCreditors("holiday");
        Holiday holiday = new Holiday(date, name);
        if (holidays.existsByDate(date)) {
            throw new ConflictException("holiday.duplicate", "Já existe feriado cadastrado nesta data");
        }
        holidays.save(holiday);
        auditTrail.record(AuditEntry.of("holiday.created", "holiday", holiday.getId())
                .created("date", holiday.getDate())
                .created("name", holiday.getName()));
        return view(holiday);
    }

    @Override
    public void remove(UUID holidayId) {
        accessScope.requireAllCreditors("holiday");
        Holiday holiday = holidays.findById(holidayId).orElseThrow(() -> ResourceNotFoundException.of("holiday", holidayId));
        holidays.delete(holiday);
        auditTrail.record(AuditEntry.of("holiday.removed", "holiday", holidayId)
                .change("date", holiday.getDate(), null)
                .change("name", holiday.getName(), null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HolidayView> list(LocalDate from, LocalDate to) {
        Guard.require(from != null && to != null && !to.isBefore(from), "holiday.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(from, to) <= MAX_RANGE_DAYS, "holiday.range_too_long",
                "Período máximo de 3 anos");
        return holidays.findByDateBetweenOrderByDateAsc(from, to).stream().map(HolidayService::view).toList();
    }

    private static HolidayView view(Holiday h) {
        return new HolidayView(h.getId(), h.getDate(), h.getName());
    }
}
