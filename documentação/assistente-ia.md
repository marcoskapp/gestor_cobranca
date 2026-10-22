# Assistente de negociação (IA)

Especificação de produto do assistente de cobrança e negociação (F24). Objetivo: automatizar o maior volume possível da operação com segurança, deixando os negociadores humanos nos casos complexos e de maior valor.

Desenho técnico: [`docs/design/assistente.md`](../docs/design/assistente.md), [`docs/design/negociacao-e-alcadas.md`](../docs/design/negociacao-e-alcadas.md) e [`docs/design/comunicacao.md`](../docs/design/comunicacao.md).

## 1. Princípio: a IA conversa, o motor decide

O modelo de linguagem **nunca define condições financeiras**. Ele entende o devedor, escolhe o próximo passo e apresenta ofertas. Toda condição (desconto, entrada, parcelas, validade) é calculada pelo motor de negociação a partir da política versionada do credor e validada no servidor pela mesma regra que vale para a tela e para a API (RB04).

O assistente age apenas por **ferramentas** com permissão explícita:

| Ferramenta | O que faz |
| --- | --- |
| Verificar identidade | Confirma que fala com o titular antes de revelar qualquer dívida |
| Consultar resumo da dívida | Títulos, saldos e acordos do devedor nos credores atendidos |
| Simular opções | Ofertas dentro do envelope permitido para o assistente |
| Registrar proposta | Grava a proposta aceita pelo devedor |
| Formalizar acordo | Gera termo e registra aceite (somente se a alçada permitir) |
| Gerar pagamento | Pix (e boleto/link quando houver provedor) |
| Registrar promessa / agendar retorno | Compromissos com data |
| Registrar opt-out | Bloqueio imediato de contato |
| Transferir para humano | Encerra a atuação do assistente no caso, com resumo e motivo |

Se o modelo propuser algo fora da política, o servidor recusa. Toda ação é auditada como de um ator do tipo `ASSISTANT`, com a transcrição da conversa guardada como evidência.

## 2. Fluxo de uma conversa

1. Mensagem chega (WhatsApp, portal, e-mail) e é associada ao devedor e ao caso.
2. **Verificação de identidade** antes de revelar a dívida (ex.: parte do CPF + data de nascimento). Revelar dívida a terceiros é cobrança abusiva (CDC, art. 42) e violação da LGPD.
3. Classificação da intenção: pagar, negociar, segunda via, contestar, "não reconheço", opt-out, falecimento, vulnerabilidade, ameaça jurídica.
4. Verificação de elegibilidade do caso e do porteiro de contato (bloqueios, horário, frequência).
5. Negociação em **escada de concessões**: começa pela melhor condição para o credor e só libera concessões condicionadas (ex.: desconto maior apenas à vista); o limite nunca é apresentado de início.
6. Aceite → termo com evidência de aceite → pagamento → acompanhamento das parcelas e lembretes.

## 3. Canais

| Canal | Papel do assistente |
| --- | --- |
| WhatsApp | Principal. Conversa completa; iniciar contato fora da janela de 24 h somente com template aprovado |
| Portal de autonegociação | Fluxo guiado e chat; canal preferencial para formalizar (devedor já autenticado) |
| E-mail | Assíncrono: respostas, termo, segunda via |
| SMS | Apenas aviso com link para portal/WhatsApp; não conversa |
| Voz | Fase futura, depende do provedor de telefonia |

## 4. Limites em três camadas

### Camada 1 — limites duros (iguais para humanos e IA)
- Política do credor: desconto máximo por componente, entrada mínima, número de parcelas, valor mínimo da parcela, validade da proposta.
- Regras de contato: horário, frequência, feriados, opt-out imediato.
- Conduta: sem ameaça, constrangimento, exposição ou informação a terceiros; respeito ao mínimo existencial (Lei 14.181/2021) quando aplicável.

### Camada 2 — alçada do assistente
O assistente tem um **perfil de alçada** próprio, como qualquer negociador, configurável por credor, carteira ou segmento. Exemplo de perfil inicial:

| Parâmetro | Exemplo inicial |
| --- | --- |
| Desconto máximo | 50% do limite da política |
| Parcelas | até 6 |
| Valor do acordo | até R$ 3.000 |
| Formalizar sozinho | sim, à vista ou até 3 parcelas |
| Renegociar acordo quebrado | uma vez |

### Camada 3 — transferência obrigatória para humano
Contestação ou "não reconheço"; suspeita de fraude; falecimento; sinais de vulnerabilidade (doença grave, desemprego, idoso em dificuldade, superendividamento); menção a advogado, Procon ou processo; agressividade; pedido explícito por humano; duas falhas seguidas de entendimento; identidade não confirmada; condição pedida acima da alçada (encaminhada a quem tem alçada suficiente).

Os gatilhos obrigatórios não podem ser desligados; outros gatilhos podem ser adicionados por carteira.

## 5. Perfis de negociador e o assistente

| Abordagem | Decisão | Motivo |
| --- | --- | --- |
| Assistente como mais um negociador na estrutura de alçadas | **Adotada** | Reaproveita o mecanismo de alçadas; começa com perfil júnior e é promovido por carteira quando os dados mostram resultado igual ou melhor que o humano |
| Copiloto com intensidade conforme a senioridade do operador | **Adotada** | Júnior: roteiro, próxima melhor oferta, alertas e bloqueio de oferta inválida. Sênior: menos condução, mais dados (score, histórico, resumo, rascunho) |
| Roteamento da transferência pelo tipo de caso | **Adotada** | Contestação → sênior/jurídico; acima da alçada → quem tem alçada; caso simples travado → qualquer operador disponível |
| "Personalidades" que concedem mais ou menos conforme o operador associado | **Rejeitada** | Para o mesmo perfil de dívida, a condição oferecida deve vir da política, não de quem atende: isonomia, previsibilidade para o credor e defesa em caso de questionamento |

## 6. Configuração (política do assistente)

Versionada e auditada, por credor → carteira → segmento (a mais específica prevalece):

| Item | Valores |
| --- | --- |
| Modo de operação | Desligado · Sombra (sugere; humano envia) · Copiloto · Autônomo com aprovação humana do acordo · Autônomo |
| Perfil de alçada | Referência a um perfil de alçada (camada 2) |
| Canais e horários | Quais canais o assistente atende e em que janela |
| Gatilhos de transferência | Obrigatórios (fixos) + adicionais configuráveis |
| Conversa | Tom (formal/próximo); máximo de interações antes de oferecer humano |
| Desligamento imediato | Por carteira, efeito instantâneo nas conversas em andamento |
| Experimentos | Testes A/B entre versões de política (F25) |

## 7. Métricas de acompanhamento

A promoção de alçada e a comparação com humanos usam, por carteira e período:

- **valor efetivamente recuperado** (pagamentos confirmados, não acordos firmados);
- taxa de quebra de acordos e tempo até o primeiro pagamento;
- desconto médio concedido e desconto como percentual do limite;
- taxa de transferência para humano e motivo;
- reclamações, opt-outs e contestações originados de conversas do assistente;
- custo por real recuperado (inclui custo do modelo e das mensagens).

## 8. Implantação recomendada

| Fase | Duração indicativa | Escopo | Critério para avançar |
| --- | --- | --- | --- |
| 1. Sombra | 2–4 semanas | Carteiras de baixo valor e atraso curto, WhatsApp receptivo | Sugestões aceitas sem edição pelos operadores na maioria dos casos; nenhuma sugestão fora da política |
| 2. Autônomo com aprovação | 4 semanas | Mesmo escopo; acordo formalizado após aprovação humana | Quebra e reclamações iguais ou menores que as dos humanos |
| 3. Autônomo | contínuo | Amplia carteiras e alçada progressivamente | Métricas da seção 7 estáveis por carteira |

Meta realista: o assistente absorver a maior parte dos casos simples e de baixo valor; os negociadores humanos concentram-se nos casos complexos e de valor alto, onde a experiência gera mais resultado.

## 9. Pendências

- Escolha do provedor de IA e da política de uso de dados (issue #12).
- Carteiras e segmentos do piloto e alçada inicial.
- Se o assistente formaliza sozinho desde o início ou apenas com aprovação humana.
- Validação jurídica do roteiro de conduta, da verificação de identidade e dos gatilhos de transferência.
