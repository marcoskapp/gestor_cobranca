package br.com.gestorcobranca.shared.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tempo limite <b>de cada comando SQL</b> das fontes do Início do portal do credor
 * ({@code app.creditor-portal.source-timeout}, padrão 2 s). Cada porta {@code :: creditor} lida pelo Início chama
 * {@link #apply()} no começo da sua transação somente leitura, depois de conferir o ator: o comando que passar do limite
 * derruba só aquela fonte (seção indisponível). Não é prazo total da fonte: uma fonte com N consultas pode levar até
 * N × o limite. Vale também para quem mais chama a porta na mesma transação (ex.: a tela Prestação de contas, por
 * {@code CreditorStatementService.openPeriod}), até o fim dela; a conexão volta ao pool com o valor padrão.
 */
@Component
public class CreditorPortalReadTimeout {

    private final StatementTimeout statementTimeout;
    private final Duration timeout;

    CreditorPortalReadTimeout(StatementTimeout statementTimeout,
                              @Value("${app.creditor-portal.source-timeout:PT2S}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("app.creditor-portal.source-timeout precisa ser positivo");
        }
        this.statementTimeout = statementTimeout;
        this.timeout = timeout;
    }

    public void apply() {
        statementTimeout.apply(timeout);
    }

    public Duration timeout() {
        return timeout;
    }
}
