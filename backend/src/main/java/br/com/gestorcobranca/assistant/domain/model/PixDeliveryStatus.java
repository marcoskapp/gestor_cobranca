package br.com.gestorcobranca.assistant.domain.model;

/**
 * Entrega do Pix gerado ao devedor. O registro no PSP é assíncrono: o pedido fica {@code AWAITING} até a
 * cobrança ficar ativa e o "copia e cola" sair pelo communication.
 */
public enum PixDeliveryStatus {
    /** turno respondido; aguardando a cobrança ativa no PSP para enviar o código */
    AWAITING,
    /** código enviado ao devedor */
    DELIVERED,
    /** o turno não terminou com resposta ao devedor: instrução cancelada, nada enviado */
    CANCELLED,
    /** não enviado (conversa saiu do assistente, porteiro negou, cobrança recusada/expirada, código inválido) */
    NOT_DELIVERED;

    public boolean open() {
        return this == AWAITING;
    }
}
