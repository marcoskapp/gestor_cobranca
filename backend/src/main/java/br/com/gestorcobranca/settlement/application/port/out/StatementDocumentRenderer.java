package br.com.gestorcobranca.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Geração do PDF resumo do demonstrativo (adaptador PDFBox). */
public interface StatementDocumentRenderer {

    byte[] render(Summary summary);

    record Line(String label, BigDecimal amount) {
    }

    /**
     * @param sections blocos de linhas (título → linhas), na ordem de exibição
     */
    record Summary(String creditorName, String creditorDocument, int sequenceNumber, LocalDate periodStart,
                   LocalDate periodEnd, String status, List<Section> sections, List<String> notes, String exportSha256,
                   Instant generatedAt) {
    }

    record Section(String title, List<Line> lines) {
    }
}
