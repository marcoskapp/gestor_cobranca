package br.com.gestorcobranca.negotiation.infrastructure.term;

import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer.TermDocument;
import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer.TermInstallment;
import br.com.gestorcobranca.negotiation.application.port.out.AgreementTermRenderer.TermTitle;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PdfAgreementTermRendererTest {

    @Test
    void rendersConditionsScheduleAndBreakRuleAcrossPages() throws Exception {
        List<TermInstallment> schedule = IntStream.rangeClosed(1, 60)
                .mapToObj(n -> new TermInstallment(n, LocalDate.of(2026, 11, 2).plusMonths(n - 1), new BigDecimal("100.00")))
                .toList();
        TermDocument doc = new TermDocument(UUID.randomUUID(), null, "Banco A", "Maria Devedora – São Paulo ✓",
                "529.***.***-25", List.of(new TermTitle("T-60", LocalDate.of(2026, 8, 24), new BigDecimal("1234.56"))),
                new BigDecimal("6100.00"), new BigDecimal("100.00"), new BigDecimal("6000.00"), BigDecimal.ZERO,
                new BigDecimal("6000.00"), schedule, "Quebra após 2 parcelas em atraso há mais de 5 dias.", 3, "PHONE",
                Instant.parse("2026-11-02T13:00:00Z"));
        byte[] pdf = new PdfAgreementTermRenderer().render(doc);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument loaded = Loader.loadPDF(pdf)) {
            assertThat(loaded.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(loaded);
            assertThat(text).contains("TERMO DE ACORDO", "Banco A", "Maria Devedora - São Paulo ?", "T-60",
                    "R$ 1.234,56", "Parcela 60", "Quebra após 2 parcelas");
        }
    }
}
