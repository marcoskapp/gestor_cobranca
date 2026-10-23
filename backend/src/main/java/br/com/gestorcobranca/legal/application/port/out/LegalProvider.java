package br.com.gestorcobranca.legal.application.port.out;

/**
 * Base das portas de integração do jurídico. Cada porta tem um adaptador ativo, escolhido por
 * {@code app.legal.providers.<porta>}; o padrão é o adaptador <b>manual</b>
 * ({@link #automatic()} falso): nada é enviado nem capturado automaticamente e o usuário lança
 * protocolos, situações e andamentos pela API. Adaptadores reais (DataJud, cartórios de protesto,
 * birôs, registro de imóveis, gravames, publicações) dependem de aprovação (issues #13 e #14) e
 * seguem docs/CONVENTIONS.md §8 — ver docs/design/juridico.md §B.4.
 */
public interface LegalProvider {

    /** Código estável do provedor (gravado nos registros: {@code manual}, {@code datajud}…). */
    String code();

    /** Falso no adaptador manual: o caso de uso não chama as operações externas. */
    boolean automatic();

    /** Resultado de envio a um provedor (protocolo atribuído e referência para acompanhamento). */
    record SubmissionReceipt(String reference, String protocol, java.time.LocalDate submittedOn) {
    }

    /**
     * Situação no provedor.
     *
     * @param status SUBMITTED, EFFECTIVE, FAILED ou REMOVED (nomes de {@code MeasureStatus})
     */
    record SubmissionStatus(String reference, String status, java.time.LocalDate effectiveOn, String detail) {
    }
}
