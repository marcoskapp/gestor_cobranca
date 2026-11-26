package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.sandbox.domain.service.DemoPdfBytes;
import br.com.gestorcobranca.shared.storage.PdfActiveContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Os PDFs fictícios que a carga envia são legíveis (a aplicação recusa PDF ilegível) e sem conteúdo ativo. */
class DemoPdfBytesTest {

    @Test
    void demoPdfIsReadableAndInert() throws Exception {
        byte[] pdf = DemoPdfBytes.of("Documento ficticio");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(document)).contains("Documento ficticio");
        }
        assertThat(PdfActiveContent.inspect(pdf)).isEqualTo(PdfActiveContent.Verdict.SAFE);
        assertThatThrownBy(() -> DemoPdfBytes.of("com (parênteses)")).isInstanceOf(IllegalArgumentException.class);
    }
}
