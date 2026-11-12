package br.com.gestorcobranca.collection.application.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sorteio da vez de desenvolvimento no "Próximo caso" da estratégia por nível (distribuicao-por-desempenho.md §4.2):
 * inteiro uniforme de 0 a 99 comparado à fatia de desenvolvimento da assessoria. Componente próprio para que os testes
 * de integração troquem o sorteio por um valor fixo (bean {@code @Primary} de teste), sem estado mutável no serviço.
 */
@Component
class DevelopmentRoll {

    /** Próximo sorteio, de 0 a 99. */
    int next() {
        return ThreadLocalRandom.current().nextInt(100);
    }
}
