package br.com.gestorcobranca.identity.domain.service;

import java.time.Instant;

/** Código TOTP válido para testes de outros pacotes (a geração é interna ao {@link Totp}). */
public final class TotpTestCodes {

    private TotpTestCodes() {
    }

    public static String current(String base32Secret, Instant now) {
        return Totp.generate(Totp.base32Decode(base32Secret), now.getEpochSecond() / 30);
    }
}
