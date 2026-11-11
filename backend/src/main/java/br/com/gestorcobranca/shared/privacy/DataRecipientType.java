package br.com.gestorcobranca.shared.privacy;

/** Quem recebeu ou teve acesso a dados pessoais do titular (LGPD art. 18, VII). */
public enum DataRecipientType {
    /** Credor dono da dívida (controlador ou quem contratou a assessoria). */
    CREDITOR,
    /** Prestador de serviço que trata dados em nome da assessoria (operador ou suboperador: mensagens, telefonia, Pix, IA). */
    PROVIDER,
    /** Escritório de advocacia parceiro responsável por caso jurídico. */
    LAW_FIRM,
    /** Sistema do credor integrado pela API pública (token de integração). */
    INTEGRATION
}
