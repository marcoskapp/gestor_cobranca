package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

/**
 * Referência a um valor de contato (telefone ou e-mail) sem o valor em claro: o blind index
 * calculado com a mesma normalização e finalidade do cadastro de contatos ({@code contact.phone},
 * {@code contact.email}) e a forma mascarada para exibição.
 */
public record ContactValueRef(ContactValueType type, String hash, String masked) {

    public ContactValueRef {
        Guard.require(type != null, "restriction.contact_type_required", "Tipo do contato obrigatório");
        Guard.require(hash != null && hash.length() == 64, "restriction.contact_hash_invalid", "Índice do contato inválido");
        Guard.require(masked != null && !masked.isBlank() && masked.length() <= 254, "restriction.contact_masked_invalid",
                "Contato mascarado inválido");
    }

    @Override
    public String toString() {
        return type + "(" + masked + ")";
    }
}
