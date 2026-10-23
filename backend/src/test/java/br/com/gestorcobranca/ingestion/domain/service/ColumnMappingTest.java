package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnMappingTest {

    private final FileLayout standard = FileLayout.standard(ImportFormat.CSV);

    @Test
    void mapsHeadersIgnoringCaseAccentsAndBomAndReportsUnknownColumns() {
        ColumnMapping mapping = ColumnMapping.resolve(standard, Arrays.asList("﻿Documento", "NOME", "id_titulo",
                "Vencimento ", "valor_principal", "garantia_3_tipo", "observação", null, ""));
        SourceRow row = mapping.map(2, List.of("52998224725", "Maria", "T-1", "10/03/2026", "100", "VEICULO", "x"));
        assertThat(row.get(CanonicalField.DEBTOR_NAME)).contains("Maria");
        assertThat(row.get(CanonicalField.DUE_DATE)).contains("10/03/2026");
        assertThat(mapping.ignoredColumns()).containsExactly("garantia_3_tipo", "observação");
    }

    @Test
    void missingRequiredOrRepeatedColumnsFailTheFile() {
        assertThatThrownBy(() -> ColumnMapping.resolve(standard, List.of("documento", "nome")))
                .isInstanceOf(ImportFileException.class).hasMessageContaining("id_titulo");
        assertThatThrownBy(() -> ColumnMapping.resolve(standard, List.of("documento", "Documento", "nome", "id_titulo",
                "vencimento", "valor_principal"))).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "import.column_ambiguous");
        assertThatThrownBy(() -> ColumnMapping.resolve(standard, List.of(" ", ""))).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "import.header_missing");
    }

    @Test
    void apiRecordsUseCanonicalKeysAndCollectUnknownOnes() {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("documento", "52998224725");
        record.put("Nome", "Maria");
        record.put("coobrigado_nome", "João");
        Set<String> unknown = new HashSet<>();
        SourceRow row = ColumnMapping.fromRecord(FileLayout.standard(ImportFormat.JSON), 1, record, unknown);
        assertThat(row.get(CanonicalField.DEBTOR_NAME)).contains("Maria");
        assertThat(unknown).containsExactly("coobrigado_nome");
    }
}
