package br.com.gestorcobranca.sandbox.application.port.out;

import br.com.gestorcobranca.sandbox.domain.model.DemoPdf;

/** Desenha um PDF fictício de demonstração (camada de texto real, como um relatório de ERP). */
public interface DemoPdfRenderer {

    byte[] render(DemoPdf.Document document);
}
