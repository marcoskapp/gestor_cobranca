package br.com.gestorcobranca.identity.domain.service;

import java.time.Instant;

/** Código TOTP válido para testes de outros pacotes (a geração é interna ao {@link Totp}). */
public final class TotpTestCodes {

    private TotpTestCodes() {
    }

    public static String current(String base32Secret, Instant now) {
        return Totp.generate(Totp.base32Decode(base32Secret), now.getEpochSecond() / 30);
    }

    /** Passo TOTP de agora (30 s). */
    public static long stepNow() {
        return Instant.now().getEpochSecond() / 30;
    }

    /** Código de um passo fixo (para testar o reuso sem depender da virada do relógio). */
    public static String codeAt(String base32Secret, long step) {
        return Totp.generate(Totp.base32Decode(base32Secret), step);
    }

    /** Código do passo atual deslocado de {@code offset} passos de 30 s (−1, 0 ou +1 são aceitos pela verificação). */
    public static String code(String base32Secret, int offset) {
        return Totp.generate(Totp.base32Decode(base32Secret), Instant.now().getEpochSecond() / 30 + offset);
    }
}
