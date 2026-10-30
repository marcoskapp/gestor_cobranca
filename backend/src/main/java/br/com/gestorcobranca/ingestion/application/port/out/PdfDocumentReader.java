package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;

/**
 * Leitura de PDF para a importação PDF inteligente: abertura segura (sem senha, sem JavaScript, estrutura válida e
 * limite de páginas), camada de texto posicionada e imagem de uma página. O conteúdo tem dados pessoais e nunca é
 * registrado em log. Problemas do arquivo lançam
 * {@link br.com.gestorcobranca.ingestion.domain.model.ImportFileException} com códigos {@code pdf_import.*}.
 */
public interface PdfDocumentReader {

    /**
     * Abre o PDF, recusa o que não for seguro e lê a camada de texto.
     *
     * @param maxPages limite de páginas aceitas
     */
    PdfInspection inspect(byte[] content, int maxPages);

    /** Camada de texto posicionada (PDF já aceito por {@link #inspect}). */
    PdfText readText(byte[] content);

    /**
     * Imagem PNG de uma página.
     *
     * @param page página (1..n)
     * @param dpi  resolução (72 = tamanho real em pontos)
     */
    RenderedPage render(byte[] content, int page, float dpi);

    /**
     * @param pages     quantidade de páginas
     * @param encrypted protegido só com senha do proprietário (abre sem senha; restrições não impedem a leitura)
     * @param text      camada de texto (pode estar vazia: PDF escaneado)
     */
    record PdfInspection(int pages, boolean encrypted, PdfText text) {
    }

    /** @param widthPt largura da página em pontos (base das coordenadas do texto) */
    record RenderedPage(int page, int widthPx, int heightPx, double widthPt, double heightPt, byte[] png) {
    }
}
