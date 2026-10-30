package br.com.gestorcobranca.ingestion.domain.model.pdf;

/** Por onde o PDF chegou. Em qualquer canal a importação só acontece com aprovação humana na tela. */
public enum PdfImportChannel {
    SCREEN,
    PUBLIC_API
}
