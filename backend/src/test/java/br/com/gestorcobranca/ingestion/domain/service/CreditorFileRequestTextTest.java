package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.service.CreditorFileRequestText.RequestLine;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditorFileRequestTextTest {

    @Test
    void identifiesTheFileByProtocolAndTheLinesByMaskedReference() {
        String text = CreditorFileRequestText.compose("Banco A", "carteira.csv", LocalDate.of(2026, 11, 20),
                "abcdef0123456789".repeat(4), List.of(new RequestLine(2, "R-7"), new RequestLine(5, "B-***98224***")), 3);
        assertThat(text).contains("Prezados(as), Banco A", "\"carteira.csv\" enviado em 20/11/2026",
                "protocolo abcdef012345)", "- Linha 2: título R-7\n- Linha 5: título B-***98224***\n- e mais 3 linhas",
                "novo arquivo sem essas linhas", "não traz nome, documento nem contato");
        assertThat(CreditorFileRequestText.protocol(null)).isEqualTo("—");
    }
}
