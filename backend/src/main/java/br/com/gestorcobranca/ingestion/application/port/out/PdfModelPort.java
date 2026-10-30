package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.shared.ai.LanguageModel;

/**
 * Modelo de linguagem da importação PDF inteligente (Amazon Bedrock, Claude — aprovado): propõe o mapa de extração a
 * partir do texto posicionado e mascarado e, em PDF sem camada de texto, lê as páginas visualmente. O adaptador só é
 * ligado com {@code app.ingestion.pdf.bedrock.enabled=true} (e a aprovação da transferência internacional); sem ele, a
 * importação usa só os mapas salvos ou o mapa escrito pelo usuário.
 */
public interface PdfModelPort extends LanguageModel {
}
