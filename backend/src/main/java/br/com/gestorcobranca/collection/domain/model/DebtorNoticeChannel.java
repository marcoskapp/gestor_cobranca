package br.com.gestorcobranca.collection.domain.model;

/** Canal da resposta ao devedor: e-mail ou WhatsApp (mensagem transacional pelo porteiro de contato) ou outro meio registrado (carta, presencial, protocolo do órgão). */
public enum DebtorNoticeChannel {
    EMAIL,
    WHATSAPP,
    OTHER
}
