package br.com.gestorcobranca.shared.privacy;

import java.util.Objects;

/**
 * Prestador configurado na assessoria que recebe dados de devedores (conta de canal, plataforma de telefonia, conta
 * Pix, provedor de IA) — base da lista "Provedor sem cadastro" do registro de operadores.
 *
 * @param recipientKey  mesma chave dos fatos ({@link DataSharingFact#recipientKey()})
 * @param recipientName nome sugerido para o cadastro
 * @param purpose       finalidade
 */
public record DataSharingRecipient(String recipientKey, String recipientName, DataSharingPurpose purpose) {

    public DataSharingRecipient {
        Objects.requireNonNull(recipientKey, "recipientKey");
        if (!DataSharingFact.KEY.matcher(recipientKey).matches()) {
            throw new IllegalArgumentException("Chave de destinatário inválida: " + recipientKey);
        }
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(purpose, "purpose");
    }
}
