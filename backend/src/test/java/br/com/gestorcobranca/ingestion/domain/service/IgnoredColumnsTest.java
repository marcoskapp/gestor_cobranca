package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoredColumnsTest {

    @Test
    void separatesColumnsReservedForTheNextLayoutVersion() {
        List<RowIssue> issues = IgnoredColumns.issues(List.of("Garantia Tipo", "coobrigado_documento", "natureza_titulo",
                "observacao", "Avaliação Valor"));
        assertThat(issues).extracting(RowIssue::code)
                .containsExactly("import.columns_not_supported", "import.columns_ignored");
        assertThat(issues).allMatch(i -> i.severity() == IssueSeverity.WARNING && i.rowNumber() == 0);
        assertThat(issues.get(0).message()).contains("Garantia Tipo", "coobrigado_documento", "natureza_titulo",
                "Avaliação Valor");
        assertThat(issues.get(1).message()).contains("observacao").doesNotContain("Garantia");
    }

    @Test
    void listsAtMostTwentyNamesAndMasksPersonalData() {
        List<String> many = IntStream.range(0, 25).mapToObj(i -> "extra" + i).toList();
        RowIssue issue = IgnoredColumns.issues(many).getFirst();
        assertThat(issue.message()).contains("extra19").doesNotContain("extra20").contains("(e mais 5)");
        assertThat(IgnoredColumns.issues(List.of("529.982.247-25")).getFirst().message()).doesNotContain("529.982.247-25");
        assertThat(IgnoredColumns.issues(List.of())).isEmpty();
    }
}
