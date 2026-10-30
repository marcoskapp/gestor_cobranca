package br.com.gestorcobranca.ingestion.domain.model.pdf;

/** Quem escreveu a revisão corrente do mapa. */
public enum MapOrigin {
    /** proposto pelo modelo a partir do texto posicionado */
    AI,
    /** mapa salvo do credor reaplicado (mesma impressão digital), sem IA */
    TEMPLATE,
    /** escrito ou ajustado pelo usuário */
    USER,
    /** PDF sem camada de texto: valores lidos visualmente pelo modelo */
    VISUAL
}
