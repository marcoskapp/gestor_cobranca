package br.com.gestorcobranca.scoring.domain.model;

import java.time.Instant;

/** Faixas horárias de 3 h no fuso de operação: 06–09, 09–12, 12–15, 15–18, 18–21 e 21–24 (madrugada fica fora). */
public final class ContactSlots {

    public static final int COUNT = 6;
    public static final int FIRST_HOUR = 6;
    public static final int WIDTH = 3;

    private ContactSlots() {
    }

    /** Índice da faixa (0–5) da hora local, ou −1 entre 0h e 6h. */
    public static int indexOf(int localHour) {
        return localHour < FIRST_HOUR ? -1 : (localHour - FIRST_HOUR) / WIDTH;
    }

    public static int startHour(int index) {
        return FIRST_HOUR + index * WIDTH;
    }

    public static int endHour(int index) {
        return startHour(index) + WIDTH;
    }

    public static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
