# Indicadores dos painéis — definições, fórmulas e denominadores

Contrato com a operação para os painéis do produto (F11 painel operacional, F18 relatórios de recuperação e produtividade; escopo §7 e M08). Cada indicador tem **fórmula e denominador explícitos**; os painéis mostram sempre a base ("1.100 de 4.000"), nunca uma taxa sozinha. Mudança de fórmula exige atualizar este documento e os testes com resultado conferido à mão (`AnalyticsIndicatorsIT`, `IndicatorsTest`). Desenho técnico em [`tecnico/desenho/analytics.md`](../tecnico/desenho/analytics.md).

## 1. Regras gerais

| Regra | Definição |
| --- | --- |
| Valor recuperado | **Pagamento confirmado** (pelo provedor, pelo extrato ou por segunda pessoa), alocado a título ou acordo, **líquido de estornos**. É a mesma base do demonstrativo de prestação de contas ao credor (recebimentos do `settlement`). |
| Acordo não pago não é recuperação | O valor acordado aparece separado ("valor acordado" × "valor pago") e nunca entra no recuperado. |
| Data do fato | Pagamento: data do pagamento. Estorno: data do estorno (o período em que o pagamento entrou não muda). Acordo: data da formalização (safra). Datas no fuso de operação (`America/Sao_Paulo`). |
| Período | Inclusive nas duas pontas; até 400 dias por consulta. |
| Taxas | Fração com 4 casas (arredondamento *half-even*), exibida em %. Denominador zero → indicador **indisponível** ("sem base no período"), nunca 0%. |
| "Dados até" | Os conjuntos consolidados são atualizados a cada 15 minutos (e sob demanda). O painel mostra o instante a partir do qual os números valem. |
| Escopo | Cada pessoa vê só os credores do seu escopo. Dados por pessoa seguem as permissões de desempenho: operador vê só o próprio, supervisor a si e às equipes que supervisiona, gestor/financeiro/auditor todos. |
| Pessoa no indicador | Negociador = quem **fechou** o acordo (formalizou). Acordos fechados pelo assistente, pelo portal ou por campanha ficam em "Sem negociador humano" quando o painel agrupa por pessoa. |

## 2. Carteira (painel operacional)

Posição na data do último refresh (o período não se aplica).

| Indicador | Fórmula | Denominador |
| --- | --- | --- |
| Valor da carteira | soma do saldo em aberto dos títulos em cobrança (`OPEN` ou `SUSPENDED`, saldo > 0) | — |
| Saldo vencido | soma do saldo em aberto dos títulos com vencimento anterior à data de referência | **participação** = saldo vencido ÷ valor da carteira |
| Saldo elegível | soma do saldo elegível dos casos não encerrados (elegível = pode ser cobrado: sem contestação bloqueante, suspensão, restrição ou jurídico) | **participação** = saldo elegível ÷ saldo em aberto dos casos não encerrados |
| Casos por estado | casos não encerrados por estado, com saldo em aberto e elegível | — |
| Fila pendente | casos elegíveis aguardando na fila (sem negociador atribuído), por fila | **participação** = casos na fila ÷ casos elegíveis |
| Faixa de atraso | dias entre o vencimento (título) ou o vencimento mais antigo (caso) e a data de referência: a vencer, até 30, 31–90, 91–180, 181–360, mais de 360 | — |

Filtros: credor e carteira. Operador, equipe e canal não se aplicam à posição (o painel avisa).

## 3. Recuperação

| Indicador | Fórmula | Denominador |
| --- | --- | --- |
| Recebido bruto | soma dos recebimentos confirmados no período | — |
| Estornado | soma dos estornos com data no período | **participação dos estornos** = estornado ÷ recebido bruto |
| **Valor recuperado líquido** | recebido bruto − estornado | — |
| **Taxa de recuperação** | recuperado líquido no período ÷ **saldo elegível atribuível no início do período** | soma, nos casos com saldo elegível > 0, do último saldo elegível informado antes de 00:00 do primeiro dia do período |
| Tempo até o recebimento | dias entre a formalização do acordo e o seu 1º recebimento, para os acordos cujo 1º recebimento caiu no período; média, mediana e percentil 90 | quantidade de acordos da amostra |
| Custo por real recuperado | comissão variável a pagar aos negociadores (extratos aprovados ou exportados) ÷ recuperado líquido, **nos meses inteiros** contidos no período | recuperado líquido dos mesmos meses |

Quebras da taxa de recuperação: por credor e carteira (do caso), por operador/equipe (o saldo é do **responsável pelo caso** no início do período; o recuperado é de quem **fechou** o acordo — mesma leitura do módulo de remuneração variável). Por canal, origem, faixa de atraso ou período a taxa não é segmentável (o saldo inicial não tem essas dimensões) e o painel mostra "não se aplica a este recorte". Recuperações de casos que entraram durante o período entram no numerador; o denominador é a carteira trabalhável no início — leitura usual de mercado, explícita para não ser confundida com "recuperado ÷ carteira recebida".

**Origem do recebimento**: Negociador (fechou sozinho, com copiloto ou negociou de fato depois da transferência do assistente), Assistente (fechou sozinho ou deixou pronto e o humano só confirmou), Portal, Campanha e Sem acordo (pagamento direto de título).

**Custo**: indisponível com qualquer recorte (credor, carteira, canal, pessoa — o extrato do negociador não se divide), para quem não vê todos os negociadores, com menos de 3 extratos (privacidade da remuneração individual) ou sem mês inteiro no período. **Custos de canal** (tarifas de WhatsApp, SMS, telefonia) ainda não existem no produto e estão listados como indicador sem fonte.

## 4. Conversão

| Indicador | Fórmula | Denominador |
| --- | --- | --- |
| Contatos efetivos | interações com tabulação de contato efetivo (pessoa certa) no período, de negociadores e do assistente | — |
| Propostas | propostas registradas no período (qualquer situação, inclusive recusadas) | — |
| Acordos | acordos formalizados no período (safra) | — |
| 1ª parcela paga | acordos da safra com a parcela nº 1 paga integralmente | — |
| Contato → proposta | propostas ÷ contatos efetivos | contatos efetivos |
| Proposta → acordo | acordos ÷ propostas | propostas |
| Acordo → 1ª parcela | acordos com 1ª parcela paga ÷ acordos | acordos da safra |
| Contato → acordo | acordos ÷ contatos efetivos | contatos efetivos |
| Valor acordado | soma do valor total dos acordos da safra | — |
| Valor pago | soma dos recebimentos dos acordos da safra (qualquer data), líquidos de estornos | **pago sobre acordado** = valor pago ÷ valor acordado |
| Parcelas pagas | parcelas de acordo pagas integralmente no período (qualquer safra) e o valor delas | — |
| Renegociações | acordos da safra que substituem outro acordo | ÷ acordos da safra |
| Quebra precoce 30/60/90 | acordos da safra **maduros** (formalizados há pelo menos a janela) quebrados até a janela ÷ acordos maduros | acordos maduros na janela |

O funil é **de fluxo**: cada etapa conta os eventos do período, não necessariamente as mesmas pessoas (por isso contato → proposta pode passar de 100% quando há propostas de contatos de períodos anteriores ou do portal). Por origem do acordo, contatos e propostas não se aplicam.

## 5. Produtividade

A partir dos fatos do módulo de remuneração variável (mesmas definições do extrato do negociador — [`remuneracao-variavel.md`](../tecnico/desenho/remuneracao-variavel.md) §2). Somente negociadores humanos.

| Indicador | Fórmula | Denominador |
| --- | --- | --- |
| Casos trabalhados | casos com ao menos uma interação da pessoa no período; por equipe e no total, soma das pessoas | — |
| Tentativas | interações de saída registradas | — |
| Contatos efetivos | interações com contato efetivo | **taxa** = contatos efetivos ÷ tentativas |
| Tempo médio de atendimento (TMA) | tempo de conversação das chamadas atendidas ÷ chamadas atendidas | chamadas atendidas |
| Horas logadas | união das sessões autenticadas por dia | — |
| Acordos | acordos formalizados pela pessoa no período | — |
| Acordos por hora logada | acordos ÷ horas logadas | horas logadas (indisponível com filtro de credor, carteira ou canal: o tempo logado não se divide) |
| Recuperado líquido | valor recuperado dos acordos fechados pela pessoa | — |

Equipe = composição atual (o cadastro de equipes não guarda histórico); uma pessoa em duas equipes conta nas duas.

## 6. Confiabilidade

| Indicador | Fórmula | Denominador |
| --- | --- | --- |
| Falha em instruções de pagamento | instruções (Pix/boleto/link) criadas no período em situação de falha, por causa | instruções criadas no período |
| Chamadas com falha | chamadas criadas no período com falha | chamadas do período |
| Extratos com falha | importações de extrato bancário com falha | importações do período |
| Mensagens com falha | mensagens enviadas (saída) no período com falha, por causa | mensagens de saída do período |
| Linhas de extrato duplicadas | linhas ignoradas por duplicidade | linhas importadas no período |
| Pagamentos em duplicidade | divergências de pagamento duplicado abertas no período | — |
| SLA de 1ª resposta estourado | conversas que passaram do limite de 1ª resposta sem resposta (evento `conversation.sla_breached`) | — |
| Divergências de conciliação | abertas no período (por tipo), em aberto agora (com valor), resolvidas no período e mediana de horas até a resolução | — |
| Processamentos em falha (DEAD) | trabalhos assíncronos esgotados, por tipo (agora) e taxa no período | trabalhos concluídos no período |
| Importações de carteira | lotes concluídos no período: falhas, mediana e p95 do tempo de processamento, linhas com erro | lotes / linhas lidas |

Processamentos não têm credor: só aparecem para quem vê todos os credores. **Mensagens duplicadas do provedor** são descartadas na entrada (só existe a métrica técnica `communication.webhook.requests{outcome=DUPLICATE}`) e estão listadas como indicador sem fonte no produto.

## 7. Exportação

Todo relatório pode ser exportado (permissão `REPORT_EXPORT`) em XLSX ou CSV (UTF-8 com BOM, separador `;`, números com ponto decimal), com os mesmos filtros e escopo da tela, em formato longo: seção, chave, rótulo, indicador, valor, numerador, denominador e observação (motivo de indisponibilidade). Cada exportação fica no log de acesso (`DATA_EXPORTED`, com o SHA-256 do arquivo).
