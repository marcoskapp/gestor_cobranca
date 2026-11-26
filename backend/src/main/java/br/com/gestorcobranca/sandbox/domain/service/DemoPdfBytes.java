package br.com.gestorcobranca.sandbox.domain.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF fictício mínimo e bem formado (uma página, só texto, tabela xref com os deslocamentos reais, sem conteúdo
 * ativo) para os comprovantes e documentos que a carga envia: a aplicação confere o conteúdo dos PDFs recebidos e
 * recusa o que não consegue ler ({@code *.evidence_unreadable}).
 */
public final class DemoPdfBytes {

    private DemoPdfBytes() {
    }

    /** @param text uma linha de texto ASCII, sem parênteses nem barra invertida */
    public static byte[] of(String text) {
        if (text == null || !text.matches("[ -'*-\\[\\]-~]*")) {
            throw new IllegalArgumentException("texto do PDF fictício: só ASCII, sem parênteses nem barra invertida");
        }
        String content = "BT /F1 12 Tf 24 100 Td (" + text + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 160] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        offsets.forEach(o -> pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", o)));
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
