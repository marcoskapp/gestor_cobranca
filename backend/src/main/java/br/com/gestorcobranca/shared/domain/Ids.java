package br.com.gestorcobranca.shared.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gerador de identificadores UUID versão 7 (RFC 9562): prefixo de 48 bits com o instante em
 * milissegundos, seguido de bits aleatórios. Mantém a ordenação temporal dos registros, o que
 * melhora a localidade dos índices B-tree no PostgreSQL em comparação com UUID v4.
 * <p>
 * Dentro do mesmo milissegundo, um contador monotônico de 12 bits ({@code rand_a}) garante
 * ordenação estrita para identificadores gerados pela mesma instância.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Clock CLOCK = Clock.systemUTC();

    private static long lastMillis = -1L;
    private static int sequence;

    private Ids() {
    }

    public static UUID newId() {
        long millis;
        int seq;
        LOCK.lock();
        try {
            millis = CLOCK.millis();
            if (millis <= lastMillis) {
                sequence++;
                if (sequence > 0xFFF) {
                    // contador esgotado no mesmo milissegundo: avança o relógio lógico
                    lastMillis++;
                    sequence = RANDOM.nextInt(0x100);
                }
                millis = lastMillis;
            } else {
                lastMillis = millis;
                sequence = RANDOM.nextInt(0x100);
            }
            seq = sequence;
        } finally {
            LOCK.unlock();
        }

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;              // versão 7
        msb |= (seq & 0xFFFL);

        long lsb = RANDOM.nextLong();
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;
        lsb |= 0x8000_0000_0000_0000L; // variante IETF

        return new UUID(msb, lsb);
    }
}
