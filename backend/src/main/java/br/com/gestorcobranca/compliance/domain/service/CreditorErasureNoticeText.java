package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Item;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Texto pronto do aviso ao credor (decisão 1479), para a pessoa copiar e enviar pelo canal da assessoria. Identifica o
 * titular só pelos dados mínimos que o credor já tem — a referência dos títulos dele, com parcela e carteira — e nunca
 * por nome, documento ou contato (o sistema não os tem mais e o aviso não pode reexpô-los). Pede ao credor a
 * eliminação no sistema dele e que as próximas cargas venham sem as linhas destes títulos.
 */
public final class CreditorErasureNoticeText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private CreditorErasureNoticeText() {
    }

    /**
     * @param creditorName   nome do credor como cadastrado (fantasia ou razão social)
     * @param portfolioNames nome de cada carteira citada (ausente = "carteira não identificada")
     * @param erasedOn       data da anonimização no fuso da operação
     */
    public static String compose(String creditorName, Map<UUID, String> portfolioNames, LocalDate erasedOn,
                                 List<Item> items) {
        StringBuilder text = new StringBuilder()
                .append("Assunto: Eliminação de dados de titular (LGPD, art. 18, § 6º)\n\n")
                .append("Prezados(as), ").append(creditorName).append(",\n\n")
                .append("Em ").append(DATE.format(erasedOn))
                .append(", eliminamos (anonimizamos) em nosso sistema os dados pessoais do titular vinculado aos títulos ")
                .append("abaixo, em atendimento à Lei Geral de Proteção de Dados. Os títulos continuam registrados, sem ")
                .append("dados que identifiquem a pessoa.\n\n");
        Map<UUID, List<Item>> byPortfolio = new LinkedHashMap<>();
        items.forEach(i -> byPortfolio.computeIfAbsent(i.getPortfolioId(), k -> new ArrayList<>()).add(i));
        byPortfolio.forEach((portfolioId, list) -> {
            text.append("Carteira ").append(portfolioNames.getOrDefault(portfolioId, "não identificada")).append(":\n");
            list.forEach(i -> {
                text.append("- Título ").append(i.getReference());
                if (i.getInstallmentNumber() != null) {
                    text.append(", parcela ").append(i.getInstallmentNumber());
                }
                text.append('\n');
            });
        });
        return text.append("\nPedimos que:\n")
                .append("1. eliminem ou anonimizem este titular também no sistema de vocês, como determina a LGPD ")
                .append("(art. 18, § 6º);\n")
                .append("2. não incluam as linhas destes títulos nos próximos arquivos de carga: o arquivo que as ")
                .append("trouxer terá essas linhas recusadas e precisará ser enviado de novo sem elas.\n\n")
                .append("Por segurança, este aviso não traz nome, documento nem contato do titular.\n")
                .toString();
    }

}
