# Portal de autonegociação (F19)

O devedor acessa um endereço com a marca do credor, vê o valor atualizado das suas dívidas, escolhe uma das condições já autorizadas pela operação, aceita o termo, fecha o acordo e paga a primeira parcela por Pix — sem falar com um negociador. O que foge disso vai para a fila de atendimento humano. Desenho técnico: [tecnico/desenho/portal.md](../tecnico/desenho/portal.md).

## Como o devedor entra

| Forma | Como funciona |
| --- | --- |
| **Link** | O operador (ficha do devedor → "Enviar link do portal") ou uma régua envia um link de uso único pelo WhatsApp, SMS ou e-mail. Vale por um prazo configurável (padrão 48 h) e uma única vez; um novo link anula o anterior. |
| **CPF/CNPJ + código** | O devedor informa o documento e recebe um código de 6 números no contato cadastrado (o canal é escolhido entre os habilitados). O código vale 10 minutos e aceita 5 tentativas. |
| **Confirmação adicional** (opcional, por credor) | Depois do código ou do link, data de nascimento ou os 4 últimos números de um contrato em cobrança. |

Segurança: a resposta ao pedido do código é sempre a mesma, exista ou não o documento (ninguém descobre quem deve); tentativas erradas bloqueiam o documento e o endereço de origem por 15 minutos, dobrando até 24 horas; a sessão dura até 1 hora (15 minutos parado encerra); o devedor pode sair, e a gestão pode encerrar qualquer sessão. Todos os acessos ficam no log de acesso.

Antes de ver as dívidas, o devedor lê e confirma o aviso de privacidade do credor; a confirmação é registrada no compliance com a base legal vigente. Mudou o texto, todos confirmam de novo.

## O que o devedor vê e faz

1. **Minhas dívidas**: só as carteiras que a operação habilitou no portal, com o valor atualizado de hoje (juros, multa e encargos pela política do credor) e as parcelas em aberto.
2. **Condições para pagar**: à vista e parcelado, só as condições dentro do perfil de alçada `PORTAL`. A escolha é revalidada no servidor; condição alterada ou fora do perfil é recusada.
3. **Confira antes de fechar**: valores, parcelas, regra de quebra ("2 parcelas sem pagamento por mais de 5 dias desfazem o acordo") e o "Li e aceito".
4. **Acordo fechado**: termo em PDF (com hash registrado), Pix da primeira parcela com QR Code e "copia e cola".
5. **Meus acordos**: parcelas, situação, 2ª via do Pix e download do termo.
6. **Ajuda**: falar com atendente, "nenhuma condição cabe no meu orçamento", "não reconheço a dívida" ou outro assunto, com canal de resposta. Gera protocolo e uma conversa na fila da caixa de entrada.

## Perfil de alçada `PORTAL` (padrão, ajustável)

| Parâmetro | Valor |
| --- | --- |
| Nível máximo da escada | 1 |
| Fração do desconto máximo da política | 50% |
| Pagamentos | até 6 (inclui a entrada) |
| Valor máximo do acordo | R$ 5.000,00 |
| Entrada mínima | 20% |
| Renegociação de acordo | não (vai ao atendimento) |
| Formaliza sozinho | sim |

O limite efetivo é sempre o menor entre a política do credor e o perfil. A gestão de alçadas pode atribuir outro perfil ao canal portal para um credor específico. Acima do perfil, o portal não oferece nem aceita; o devedor é convidado a falar com um atendente.

## Configuração (gestão: "Portal de autonegociação")

Por credor: endereço (`/portal/<nome>`), ativo ou não, nome e cores (contraste mínimo verificado), logotipo, carteiras habilitadas, canais do código, confirmação adicional, validade do link, aviso de privacidade e texto de atendimento. A tela mostra o funil dos últimos 30 dias e as sessões, com opção de encerrar.

Antes de ativar: publicar os templates `PORTAL_CODIGO_<CANAL>`, `PORTAL_LINK_<CANAL>` e `PORTAL_ATENDIMENTO_<CANAL>` nos canais habilitados (WhatsApp exige aprovação no provedor) e ter a política de negociação do credor publicada e uma conta Pix ativa.

## Indicadores

Funil por sessão: **acesso → simulação → aceite → Pix gerado → pagamento**, por portal e período, nos eventos `portal.*` e na métrica `portal.funnel`. Acordos fechados no portal têm origem `PORTAL_SELF_SERVICE` na remuneração variável: o crédito vai ao último negociador com contato efetivo nos 7 dias anteriores (regra do incentive), senão a ninguém.

## Pedidos que vão para pessoas

| Pedido | O que acontece |
| --- | --- |
| Falar com atendente / outro assunto | Conversa na fila (motivo "devedor pediu humano") |
| Nenhuma condição cabe | Conversa na fila (motivo "negociação do portal"): o negociador pode propor outra condição dentro da sua alçada |
| Não reconheço a dívida | Conversa na fila (motivo "contestação"): o atendente registra a contestação no caso |

Se não houver como abrir a conversa (sem contato no canal, template ausente), o pedido aparece em "Pedidos do portal" para a operação retornar.
