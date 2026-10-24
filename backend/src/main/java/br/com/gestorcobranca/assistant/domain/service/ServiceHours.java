package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.PolicySettings.ServiceWindow;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/** Janelas de atendimento do assistente (fuso de operação; fim exclusivo; início = fim = dia inteiro). */
public final class ServiceHours {

    private ServiceHours() {
    }

    public static boolean isOpen(List<ServiceWindow> windows, ZonedDateTime at) {
        if (windows == null || windows.isEmpty()) {
            return true;
        }
        LocalTime time = at.toLocalTime();
        return windows.stream().anyMatch(w -> w.days().contains(at.getDayOfWeek())
                && (w.start().equals(w.end()) || (!time.isBefore(w.start()) && time.isBefore(w.end()))));
    }

    /** Próxima abertura (até 8 dias à frente); vazio se nenhuma janela abre. */
    public static Optional<ZonedDateTime> nextOpening(List<ServiceWindow> windows, ZonedDateTime from) {
        if (isOpen(windows, from)) {
            return Optional.of(from);
        }
        ZonedDateTime best = null;
        for (int d = 0; d <= 8; d++) {
            ZonedDateTime day = from.toLocalDate().plusDays(d).atStartOfDay(from.getZone());
            for (ServiceWindow w : windows) {
                if (!w.days().contains(day.getDayOfWeek())) {
                    continue;
                }
                ZonedDateTime opening = day.with(w.start());
                if (opening.isAfter(from) && (best == null || opening.isBefore(best))) {
                    best = opening;
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }
}
