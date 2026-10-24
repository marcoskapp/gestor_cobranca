# Assistente de negociação (IA)

Especificação de produto do assistente de cobrança e negociação (F24). Objetivo: automatizar o maior volume possível da operação com segurança, deixando os negociadores humanos nos casos complexos e de maior valor.

Desenho técnico: [`docs/tecnico/desenho/assistente.md`](../tecnico/desenho/assistente.md), [`docs/tecnico/desenho/negociacao-e-alcadas.md`](../tecnico/desenho/negociacao-e-alcadas.md) e [`docs/tecnico/desenho/comunicacao.md`](../tecnico/desenho/comunicacao.md).

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
| Gerar pagamento | Pix imediato da parcela de acordo do próprio devedor, quando a conversa foi amigável e a conciliação tranquila (§10); boleto/link quando houver provedor |
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
| Pix na conversa | Desligado por padrão; o gestor liga por política e define o valor máximo (padrão R$ 5.000), Pix por conversa no dia (3), validade (60 min) e a janela de risco (30 dias) — §10 |

## 7. Métricas de acompanhamento

A promoção de alçada e a comparação com humanos usam, por carteira e período:

- **valor efetivamente recuperado** (pagamentos confirmados, não acordos firmados);
- taxa de quebra de acordos e tempo até o primeiro pagamento;
- desconto médio concedido e desconto como percentual do limite;
- taxa de transferência para humano e motivo;
- reclamações, opt-outs e contestações originados de conversas do assistente;
- custo por real recuperado (inclui custo do modelo e das mensagens);
- Pix na conversa: gerados, recusados por motivo, pagos em até 24 h e conversão Pix gerado → pago.

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
- Envio do QR Code como imagem no WhatsApp (o communication ainda não envia mídia; hoje vai o "copia e cola").

## 10. Pix na conversa

**Decisão do responsável (24/10/2026):** *"PIX na conversa: se a conversa foi amigável e a conciliação com o devedor tranquila, o Pix pode ser gerado pelo assistente IA."* (substitui a decisão 135, que deixava o Pix só para humanos).

**Quando o assistente gera o Pix.** Só quando **todos** os critérios abaixo são atendidos — verificados pelo sistema, nunca pela IA sozinha:

1. a política do assistente liga o Pix na conversa (desligado por padrão; ligado pelo gestor);
2. o assistente atua no modo autônomo ou autônomo com aprovação — neste, só para acordo já formalizado por um humano; nos modos sombra e copiloto ele apenas **sugere** ao operador, que gera pela tela;
3. a identidade do devedor foi confirmada na conversa;
4. existe acordo formalizado e vigente do mesmo devedor e credor da conversa, com a parcela (ou entrada) em aberto — o valor do Pix é **exatamente** o da parcela; a IA escolhe a parcela, nunca o valor;
5. a conversa foi amigável: nos últimos 30 dias nenhuma transferência por contestação, alegação de pagamento, fraude, falecimento, vulnerabilidade, advogado/Procon/processo, reclamação, agressividade, pedido de humano, opt-out, falha de entendimento, identidade não confirmada ou gatilho da política; nenhum guardrail acionado; nada disso na mensagem atual;
6. nenhuma contestação aberta no caso;
7. o devedor pediu ou aceitou explicitamente pagar agora ("manda o Pix", "quero pagar agora", "aceito"), sem negação ou adiamento;
8. valor até o limite da política (padrão R$ 5.000) e no máximo 3 Pix na conversa por dia.

Se algum critério falhar, o assistente não gera o Pix; quando um atendente pode concluir o pagamento (valor acima do limite, risco na conversa, contestação, limite diário, acordo não formalizado por humano no modo com aprovação), ele informa que um atendente vai ajudar e transfere a conversa. Todos os critérios avaliados ficam na trilha do turno.

**O que o devedor recebe.** Uma mensagem separada, montada pelo sistema: valor e validade (padrão 60 minutos), o código "Pix Copia e Cola" e um aviso de segurança — conferir no app do banco o nome do recebedor (a conta Pix do credor ou da assessoria) e que nunca pedimos pagamento para chave Pix de pessoa física. O código é sempre uma cobrança do PSP da conta Pix do credor/assessoria (Pix imediato, sem enviar dados do pagador ao PSP); código com chave Pix, de outro recebedor ou com valor diferente da parcela não é enviado. O QR Code em imagem depende do envio de mídia pelo WhatsApp (pendência).

**Por que R$ 5.000 como limite padrão.** Cobre a grande maioria das parcelas e entradas de acordos de varejo, cartão e consumo (tíquete típico bem abaixo disso), fica alinhado ao valor máximo do perfil `PORTAL` de autoatendimento (decisão 139) e limita a exposição em caso de engenharia social; acima dele, um atendente gera o Pix. Ajustável por política até R$ 50.000, na tela da política do assistente (cartão "Pix na conversa").

**Acompanhamento.** Pix gerados, recusados por motivo, sugeridos, entregues, pagos e pagos em até 24 h, e a conversão Pix gerado → pago entram no painel de qualidade do assistente; o Pix não muda a atribuição do acordo (quem fechou continua com o crédito).
