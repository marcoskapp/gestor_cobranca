package br.com.gestorcobranca.telephony.domain.model;

/**
 * Estado do operador para o discador (ACD). Transições:
 * <pre>
 * OFFLINE ──login──► AVAILABLE ◄──────────── tabulação ─── WRAP_UP
 *    ▲                 │   ▲  └──chamada──► ON_CALL ──fim──┘
 *    └──logout/timeout─┤   └──fim da pausa── PAUSED
 *                      └──pausa(motivo)─────►
 * </pre>
 * {@code ON_CALL} e {@code WRAP_UP} são controlados pelas chamadas; o operador só escolhe
 * disponível, pausa (com motivo) e saída.
 */
public enum PresenceStatus {
    OFFLINE,
    AVAILABLE,
    ON_CALL,
    WRAP_UP,
    PAUSED;

    /** Conta como tempo logado. */
    public boolean isLoggedIn() {
        return this != OFFLINE;
    }
}
