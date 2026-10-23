package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSignatureTest {

    @Test
    void acceptsMatchingContent() {
        assertThatCode(() -> FileSignature.check(ImportFormat.CSV, "documento;nome\n".getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
        assertThatCode(() -> FileSignature.check(ImportFormat.XLSX, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14}))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsContentThatDoesNotMatchTheExtension() {
        assertThatThrownBy(() -> FileSignature.check(ImportFormat.XLSX, "a;b".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.file_type_invalid");
        assertThatThrownBy(() -> FileSignature.check(ImportFormat.CSV, new byte[]{0x50, 0x4B, 0x03, 0x04}))
                .isInstanceOf(ImportFileException.class);
        assertThatThrownBy(() -> FileSignature.check(ImportFormat.CSV, new byte[]{0x4D, 0x5A, 0x00}))
                .isInstanceOf(ImportFileException.class);
        assertThatThrownBy(() -> FileSignature.check(ImportFormat.CSV, new byte[]{'a', 0, 'b'}))
                .isInstanceOf(ImportFileException.class);
        assertThatThrownBy(() -> FileSignature.check(ImportFormat.CSV, new byte[0]))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.file_empty");
    }
}
