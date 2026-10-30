package br.com.gestorcobranca.sandbox.application.service.catalog;

import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;

import java.util.List;

/**
 * Templates de mensagem do sandbox, com os textos propostos em {@code docs/negocio/templates-cobranca.md} (ainda em
 * revisão pelo jurídico) e os exigidos pelo portal. O nome da assessoria entra no lugar de {@code [NOME DA ASSESSORIA]}.
 */
public final class TemplateCatalog {

    private static final String AGENCY = "[NOME DA ASSESSORIA]";
    private static final String OPT_OUT = "\n\nPara não receber mais mensagens, responda SAIR.";

    public record Template(String code, String channel, String name, String purpose, String subject, String body,
                           List<VariableInput> variables) {

        public Template withAgency(String agency) {
            return new Template(code, channel, name, purpose, subject == null ? null : subject.replace(AGENCY, agency),
                    body.replace(AGENCY, agency), variables);
        }
    }

    private TemplateCatalog() {
    }

    public static List<Template> all() {
        VariableInput nome = new VariableInput("nome", "FIRST_NAME", true, null);
        VariableInput credor = new VariableInput("credor", "TEXT", true, null);
        return List.of(
                new Template("COBRANCA_ABERTURA", "WHATSAPP", "Abertura sem expor a dívida", "COLLECTION", null,
                        "Olá, {{nome}}! Aqui é da " + AGENCY + ". Temos um assunto do seu interesse para tratar com você.\n"
                                + "Para proteger seus dados, só passamos os detalhes depois de confirmar que estamos falando "
                                + "com a pessoa certa.\nPodemos continuar por aqui?\n\nSe não quiser receber nossas "
                                + "mensagens, responda SAIR.", List.of(nome)),
                new Template("COBRANCA_RETOMADA", "WHATSAPP", "Retomada sem resposta", "COLLECTION", null,
                        "Olá, {{nome}}. Tentamos falar com você nos últimos dias sobre um assunto do seu interesse.\n"
                                + "Quando for melhor para você, é só responder esta mensagem que seguimos por aqui." + OPT_OUT,
                        List.of(nome)),
                new Template("COBRANCA_SALDO", "WHATSAPP", "Saldo e atraso", "COLLECTION", null,
                        "Olá, {{nome}}. Aqui é da " + AGENCY + ", em nome de {{credor}}.\nConsta em aberto o valor de "
                                + "{{saldo}}, com {{dias}} dias de atraso.\nPodemos ajudar a encontrar a melhor forma de "
                                + "regularizar. Responda esta mensagem para conversar." + OPT_OUT,
                        List.of(nome, credor, new VariableInput("saldo", "MONEY", true, null),
                                new VariableInput("dias", "INTEGER", true, null))),
                new Template("ACORDO_LEMBRETE_PARCELA", "WHATSAPP", "Lembrete de parcela do acordo", "REMINDER", null,
                        "Olá, {{nome}}. Aqui é da " + AGENCY + ", em nome de {{credor}}.\nLembramos que a parcela do seu "
                                + "acordo, no valor de {{valor}}, vence em {{vencimento}}.\nSe já pagou, desconsidere esta "
                                + "mensagem. Dúvidas? É só responder por aqui." + OPT_OUT,
                        List.of(nome, credor, new VariableInput("valor", "MONEY", true, null),
                                new VariableInput("vencimento", "DATE", true, null))),
                new Template("COBRANCA_SALDO_EMAIL", "EMAIL", "Saldo e atraso (e-mail)", "COLLECTION",
                        "Pendência financeira — " + AGENCY,
                        "Olá, {{nome}}.\n\nA " + AGENCY + ", em nome de {{credor}}, informa que consta em aberto o valor de "
                                + "{{saldo}}, com {{dias}} dias de atraso.\nResponda este e-mail para conhecer as opções "
                                + "de regularização.\n\nEsta é uma mensagem de um ambiente de demonstração com dados "
                                + "fictícios.",
                        List.of(nome, credor, new VariableInput("saldo", "MONEY", true, null),
                                new VariableInput("dias", "INTEGER", true, null))),
                new Template("COBRANCA_RETOMADA_SMS", "SMS", "Retomada por SMS", "COLLECTION", null,
                        AGENCY + ": {{nome}}, temos um assunto do seu interesse. Responda esta mensagem. SAIR para não "
                                + "receber.", List.of(nome)),
                new Template("PORTAL_CODIGO_WHATSAPP", "WHATSAPP", "Portal — código de acesso", "SERVICE", null,
                        "Seu código de acesso é {{codigo}}. Vale por {{minutos}} minutos.",
                        List.of(new VariableInput("codigo", "CODE", true, null),
                                new VariableInput("minutos", "INTEGER", true, null))),
                new Template("PORTAL_LINK_WHATSAPP", "WHATSAPP", "Portal — link de acesso", "SERVICE", null,
                        "Acesse o portal de negociação: {{link}} (vale por {{horas}} horas).",
                        List.of(new VariableInput("link", "URL", true, null),
                                new VariableInput("horas", "INTEGER", true, null))),
                new Template("PORTAL_ATENDIMENTO_WHATSAPP", "WHATSAPP", "Portal — pedido de atendimento", "SERVICE", null,
                        "Recebemos seu pedido {{protocolo}}. Um atendente vai responder por aqui.",
                        List.of(new VariableInput("protocolo", "CODE", true, null))),
                new Template("PORTAL_CODIGO_EMAIL", "EMAIL", "Portal — código de acesso (e-mail)", "SERVICE",
                        "Seu código de acesso — " + AGENCY,
                        "Seu código de acesso ao portal é {{codigo}}. Ele vale por {{minutos}} minutos.",
                        List.of(new VariableInput("codigo", "CODE", true, null),
                                new VariableInput("minutos", "INTEGER", true, null))),
                new Template("PORTAL_LINK_EMAIL", "EMAIL", "Portal — link de acesso (e-mail)", "SERVICE",
                        "Seu link de acesso — " + AGENCY,
                        "Acesse o portal de negociação: {{link}}\nO link vale por {{horas}} horas e só pode ser usado uma vez.",
                        List.of(new VariableInput("link", "URL", true, null),
                                new VariableInput("horas", "INTEGER", true, null))),
                new Template("PORTAL_ATENDIMENTO_EMAIL", "EMAIL", "Portal — pedido de atendimento (e-mail)", "SERVICE",
                        "Recebemos seu pedido — " + AGENCY,
                        "Recebemos seu pedido {{protocolo}}. Um atendente vai responder por e-mail.",
                        List.of(new VariableInput("protocolo", "CODE", true, null))));
    }
}
