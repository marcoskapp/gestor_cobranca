package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;

/**
 * Converte os avisos devolvidos pelo módulo debt ({@code UpsertResult.warnings()}, códigos
 * estáveis) em ocorrências do relatório, com mensagem legível e sem dados pessoais.
 */
public final class DebtWarnings {

    private DebtWarnings() {
    }

    public static RowIssue toIssue(long rowNumber, String code) {
        return RowIssue.of(rowNumber, IssueSeverity.WARNING, field(code),
                code, message(code));
    }

    static String message(String code) {
        if (code == null) {
            return "Aviso do cadastro";
        }
        return switch (code) {
            case "title.locked_status" ->
                    "Título encerrado na base (quitado, cancelado ou devolvido): dados do título não alterados";
            case "title.portfolio_mismatch" ->
                    "Título pertence a outra carteira: carteira não alterada (use a transferência de carteira)";
            case "obligation_party.ended" ->
                    "Participação do coobrigado já encerrada (ex.: exoneração): não reaberta pela carga";
            case "collateral.type_mismatch" ->
                    "Garantia já cadastrada com outro tipo: tipo não alterado pela carga (revise o cadastro)";
            case "contact.phone_invalid" -> "Telefone inválido: contato ignorado";
            case "contact.email_invalid" -> "E-mail inválido: contato ignorado";
            case "contact.address_invalid" -> "Endereço inválido: contato ignorado";
            case "contact.person_role_invalid" -> "Papel da pessoa de contato inválido: papel ignorado";
            case "contact.person_name_too_long" -> "Nome da pessoa de contato muito longo: nome ignorado";
            case "representative.company_required" -> "Representante só vale para devedor com CNPJ: dado ignorado";
            case "representative.cpf_required" -> "Representante deve ter CPF válido: dado ignorado";
            case "representative.role_invalid" -> "Papel do representante inválido: representante ignorado";
            default -> code.startsWith("contact.") ? "Contato inválido: contato ignorado"
                    : code.startsWith("representative.") ? "Representante não gravado: revise os dados do representante"
                    : "Aviso do cadastro: " + code;
        };
    }

    private static String field(String code) {
        if (code == null) {
            return null;
        }
        if (code.startsWith("contact.person")) {
            return "contato";
        }
        if (code.startsWith("representative.")) {
            return "representante";
        }
        if (code.startsWith("contact.phone")) {
            return "telefone";
        }
        if (code.startsWith("contact.email")) {
            return "email";
        }
        if (code.startsWith("contact.address")) {
            return "endereco";
        }
        if (code.startsWith("collateral.")) {
            return "garantia_tipo";
        }
        if (code.startsWith("obligation_party.")) {
            return "coobrigado";
        }
        return code.startsWith("title.") ? "id_titulo" : null;
    }
}
