package br.com.gestorcobranca.portal.domain.model;

/** Canais do communication usados pelo portal (código, link e retorno do atendimento). */
public enum PortalChannel {
    WHATSAPP, SMS, EMAIL;

    /** Tipo de contato do devedor no debt usado pelo canal. */
    public String contactType() {
        return this == EMAIL ? "EMAIL" : "PHONE";
    }
}
