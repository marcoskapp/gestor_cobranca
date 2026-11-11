package br.com.gestorcobranca.compliance.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** O log da fonte indisponível leva o SQLState da causa (sem a mensagem, que pode trazer parâmetros da consulta). */
class DataSharingCollectorTest {

    @Test
    void sqlStateComesFromTheFirstSqlExceptionInTheChain() {
        SQLException timeout = new SQLException("canceling statement due to statement timeout", "57014");
        assertThat(DataSharingCollector.sqlState(new DataAccessResourceFailureException("falha", timeout)))
                .isEqualTo("57014");
        assertThat(DataSharingCollector.sqlState(new IllegalStateException("sem SQL"))).isNull();
    }
}
