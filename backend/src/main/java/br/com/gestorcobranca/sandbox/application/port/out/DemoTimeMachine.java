package br.com.gestorcobranca.sandbox.application.port.out;

import java.time.Instant;

/**
 * Relógio de demonstração: desloca o relógio da aplicação inteira ({@code Clock} injetado em serviços, entidades,
 * consumidores de eventos e jobs) para uma data passada, para a carga do sandbox registrar o histórico fictício pelos
 * casos de uso, com as datas daquele período. O tempo continua andando a partir do instante escolhido.
 * <p>
 * Só existe no perfil {@code sandbox} com a carga ligada. O deslocamento é global (todas as threads), nunca para o
 * futuro, fica só em memória (reiniciar a aplicação volta ao tempo real) e a carga sempre volta ao presente ao terminar
 * (inclusive em falha).
 */
public interface DemoTimeMachine {

    /** Desloca o relógio para que "agora" seja {@code instant} (no passado; o futuro é recusado). */
    void travelTo(Instant instant);

    /** Volta ao tempo real. */
    void backToPresent();

    boolean shifted();

    /** Hora real, independente do deslocamento. */
    Instant realNow();
}
