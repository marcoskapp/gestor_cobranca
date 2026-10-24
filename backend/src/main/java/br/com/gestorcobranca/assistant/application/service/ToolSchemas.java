package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolDefinition;

/** Definições (nome, descrição e JSON Schema) das ferramentas oferecidas ao modelo. */
final class ToolSchemas {

    static final ToolDefinition REGISTER_INTENT = new ToolDefinition(AssistantToolbox.REGISTER_INTENT,
            "Chame primeiro em todo turno: registra a intenção do devedor, sua confiança no entendimento (0 a 1) e "
                    + "sinais que exigem atendimento humano (contestação, falecimento, vulnerabilidade, menção a "
                    + "advogado/Procon/processo, reclamação, agressividade, pedido de humano).",
            """
            {"type":"object","additionalProperties":false,"required":["intent","confidence"],
             "properties":{
              "intent":{"type":"string","enum":["GREETING","IDENTITY","BALANCE","NEGOTIATE","ACCEPT","PAYMENT","PROMISE","CALLBACK","QUESTION","OTHER"]},
              "confidence":{"type":"number","minimum":0,"maximum":1},
              "signals":{"type":"array","items":{"type":"string","enum":["DISPUTE","PAYMENT_CLAIM","FRAUD_SUSPECTED","DECEASED","VULNERABILITY","LEGAL_MENTION","COMPLAINT","ABUSIVE","HUMAN_REQUESTED"]}}}}
            """);

    static final ToolDefinition DEBT_SUMMARY = new ToolDefinition(AssistantToolbox.DEBT_SUMMARY,
            "Consulta o saldo atualizado da dívida no credor desta conversa (calculado pelo motor de negociação).",
            """
            {"type":"object","additionalProperties":false,"properties":{}}
            """);

    static final ToolDefinition SIMULATE = new ToolDefinition(AssistantToolbox.SIMULATE,
            "Simula opções de pagamento dentro da alçada do assistente. Comece pelo nível 0; níveis acima de 0 "
                    + "exigem o motivo da concessão. Devolve ofertas com referência (ex.: O1) para usar em propose_offer.",
            """
            {"type":"object","additionalProperties":false,
             "properties":{
              "installments":{"type":"array","maxItems":3,"items":{"type":"integer","minimum":1,"maximum":60},
                              "description":"número de pagamentos, incluindo a entrada (1 = à vista)"},
              "level":{"type":"integer","minimum":0,"description":"nível da escada de concessões"},
              "reason":{"type":"string","enum":["PAYMENT_CAPACITY","INCOME_LOSS","HEALTH_OR_FAMILY","COMPETING_OFFER","LOW_RECOVERY_PROSPECT","IMMEDIATE_PAYMENT"]}}}
            """);

    static final ToolDefinition PROPOSE = new ToolDefinition(AssistantToolbox.PROPOSE,
            "Registra como proposta uma oferta devolvida por simulate_options. Oferta fora da alçada é encaminhada "
                    + "a um humano (o turno termina).",
            """
            {"type":"object","additionalProperties":false,"required":["offer"],
             "properties":{"offer":{"type":"string","description":"referência da oferta, ex.: O2"}}}
            """);

    static final ToolDefinition ACCEPT = new ToolDefinition(AssistantToolbox.ACCEPT,
            "Registra o aceite do devedor para a proposta (somente se ele aceitou explicitamente nesta mensagem).",
            """
            {"type":"object","additionalProperties":false,"required":["proposal"],
             "properties":{"proposal":{"type":"string","description":"referência da proposta, ex.: P1"}}}
            """);

    static final ToolDefinition PROMISE = new ToolDefinition(AssistantToolbox.PROMISE,
            "Registra promessa de pagamento sem acordo formal. O valor precisa ser um valor devolvido pelas ferramentas.",
            """
            {"type":"object","additionalProperties":false,"required":["amount","date"],
             "properties":{"amount":{"type":"number"},"date":{"type":"string","format":"date"}}}
            """);

    static final ToolDefinition CALLBACK = new ToolDefinition(AssistantToolbox.CALLBACK,
            "Agenda um retorno de um atendente no horário combinado (até 15 dias).",
            """
            {"type":"object","additionalProperties":false,"required":["dateTime"],
             "properties":{"dateTime":{"type":"string","description":"aaaa-mm-ddThh:mm, horário de Brasília"}}}
            """);

    static final ToolDefinition TRANSFER = new ToolDefinition(AssistantToolbox.TRANSFER,
            "Transfere a conversa para um atendente humano com motivo e um resumo curto sem dados pessoais.",
            """
            {"type":"object","additionalProperties":false,"required":["reason"],
             "properties":{
              "reason":{"type":"string","enum":["DISPUTE","PAYMENT_CLAIM","FRAUD_SUSPECTED","DECEASED","VULNERABILITY","LEGAL_MENTION","COMPLAINT","ABUSIVE","HUMAN_REQUESTED","LOW_CONFIDENCE","MODEL_REQUESTED"]},
              "summary":{"type":"string","maxLength":500}}}
            """);

    static final ToolDefinition INSTALLMENTS = new ToolDefinition(AssistantToolbox.INSTALLMENTS,
            "Lista as parcelas em aberto dos acordos vigentes do devedor nesta conversa (referências I1, I2…, com "
                    + "número, vencimento e valor calculados pelo sistema).",
            """
            {"type":"object","additionalProperties":false,"properties":{}}
            """);

    static final ToolDefinition PAYMENT = new ToolDefinition(AssistantToolbox.PAYMENT,
            "Pede o Pix da parcela indicada (referência de get_installments). Use só quando o devedor pedir ou aceitar "
                    + "explicitamente pagar agora. O sistema avalia os critérios e, se aprovado, envia o código em "
                    + "mensagem separada; o valor é sempre o da parcela. Se recusado, não insista.",
            """
            {"type":"object","additionalProperties":false,"required":["installment"],
             "properties":{"installment":{"type":"string","description":"referência da parcela, ex.: I1"}}}
            """);

    private ToolSchemas() {
    }
}
