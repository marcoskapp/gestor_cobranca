package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Document;

/** Natureza do devedor, sempre derivada do documento (CPF → pessoa física, CNPJ → jurídica). */
public enum PersonType {
    INDIVIDUAL,
    COMPANY;

    public static PersonType of(Document document) {
        return document.type() == Document.DocumentType.CPF ? INDIVIDUAL : COMPANY;
    }
}
