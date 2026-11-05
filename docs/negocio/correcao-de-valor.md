# Correção manual do valor do título

Como a assessoria corrige o valor de um título (erro de cálculo, cobrança em duplicidade, encargo acima do contrato, contestação procedente…), com motivo, evidência, revisão por segunda pessoa acima de um limite (RB06) e histórico no próprio título. Aprovado pelo responsável em 05/11/2026; decisões 802–813 em [decisoes-autonomas.md](decisoes-autonomas.md).

## 1. Quem pode

| Permissão | O que permite | Perfis padrão |
| --- | --- | --- |
| `TITLE_AMOUNT_ADJUST` | pedir a correção (aplicada na hora até o limite), retirar o próprio pedido, decidir as divergências com a carga do credor | Gestor, Financeiro (e Administrador) |
| `TITLE_AMOUNT_APPROVE` | aprovar ou recusar pedidos **de outra pessoa**; definir o limite (só quem vê todos os credores) | Gestor, Financeiro (e Administrador) |

O **operador nunca recebe** essas permissões por padrão. O histórico de correções é visível a quem vê o título (`DEBTOR_VIEW` ou `CASE_VIEW`, no escopo de credores).

## 2. O pedido

- O usuário informa o **novo saldo de cada componente** a corrigir (principal, juros, multa, encargos, honorários), com a máscara monetária.
- **Motivo** da lista — erro de cálculo ou digitação, valor confirmado pelo credor, contestação procedente, cobrança em duplicidade, encargo acima do contrato, dispensa parcial autorizada pelo credor, outro — mais **descrição** obrigatória (texto livre, guardado criptografado).
- **Evidência** opcional (PDF, PNG ou JPEG até 20 MB, tipo conferido pelo conteúdo) e **vínculo opcional com a contestação** (o atalho da contestação já preenche). A evidência é enviada ao armazenamento **antes** de o título ser travado para o pedido (decisão 870): pagamentos, estornos e cargas do mesmo título não esperam o upload; o saldo do pedido é lido já com o título travado, e o arquivo de um pedido recusado é removido.
- Regras:
  - nenhum componente fica negativo;
  - a correção não pode zerar o saldo: para encerrar a cobrança, cancele o título;
  - mover valor entre componentes sem mudar o total não é correção.

## 3. Autorização (RB06: evidência e revisão por segunda pessoa)

| Situação | O que acontece |
| --- | --- |
| **Redução pura até o limite** da assessoria (padrão R$ 200,00): nenhum componente sobe e a **soma das reduções** cabe no limite | aplicada no registro, por quem pediu |
| **Redução acima do limite** (a soma das reduções dos componentes, não o líquido) | aguarda a aprovação de **outra pessoa** com `TITLE_AMOUNT_APPROVE`; evidência ou contestação obrigatória |
| **Qualquer aumento** do saldo ou de **algum componente** (mesmo com o saldo final menor, ex.: principal para baixo e honorários para cima) | sempre aguarda a aprovação de outra pessoa, com evidência ou contestação |

- **Segregação de funções**: quem pede nunca aprova nem recusa o próprio pedido, nem o administrador. A regra vale no domínio e no banco, e a fila de aprovação não mostra os próprios pedidos.
- **Um pedido pendente por título.** Quem pediu pode retirar o pedido antes da decisão.
- **Aprovação sobre a base do pedido**: se o saldo mudou depois do pedido (pagamento, carga ou outra correção), a aprovação é recusada. O pedido é então recusado e feito de novo sobre os valores atuais, para não aplicar uma correção sobre outra base.
- **Recusa** exige motivo.
- O **limite** é por assessoria. Zero faz toda correção passar por aprovação; o máximo é R$ 100.000,00. **Reduzir** o limite vale na hora; **aumentar** fica proposto até **outra pessoa** com `TITLE_AMOUNT_APPROVE` e acesso a todos os credores confirmar (pendência "Aumento do limite de correção de valor para confirmar"), para que ninguém suba o limite, aplique a própria redução e volte o limite. Salvar o limite atual ou um menor descarta a proposta em aberto.
- Pendente de decisão: **limite acumulado por título** numa janela de tempo (reduções sucessivas dentro do limite no mesmo título), no modelo do pagamento externo fracionado (decisão 309).

## 4. Efeito no título, em acordos e saldos

- A correção entra no **razão do título** como ajuste referenciado ao pedido, por componente. O saldo em aberto muda e o principal acompanha o componente principal.
- **Pagamentos já recebidos não mudam.**
- **Título em acordo em vigor não é corrigido.** O acordo fixou o valor negociado com o devedor; o padrão de mercado é renegociar (ou cancelar) o acordo e só então corrigir. A tela mostra o motivo e o caminho.
- **Título fora de cobrança** (quitado, cancelado, devolvido) não é corrigido.
- Mudou o saldo, a cobrança reage como em qualquer mudança de saldo:
  - caso e elegibilidade são reavaliados;
  - o "ajuste sugerido" de uma contestação procedente é concluído automaticamente quando o saldo muda.

## 5. Carga do credor depois da correção

A carga **não sobrescreve a correção em silêncio**:

| Carga informa | O que acontece |
| --- | --- |
| o mesmo valor corrigido | o credor convergiu: a correção deixa de ser "manual" e uma divergência aberta é encerrada |
| outro valor | a correção é **mantida**; abre uma **divergência** (uma por título) e a linha da carga recebe o aviso `title.principal_divergence` |
| o mesmo valor de uma divergência em aberto | só conta a ocorrência |

A divergência é decidida por quem tem `TITLE_AMOUNT_ADJUST`, sempre com motivo:

- **manter a correção**: o valor do credor fica reconhecido e as próximas cargas com ele não reabrem a divergência; um valor novo reabre;
- **aceitar o valor do credor**: lançado no razão, e a correção manual deixa de valer.

## 6. Histórico, logs e pendências

- **No título** (aba "Correções de valor"): cada pedido com valor anterior e novo, componentes afetados, motivo e descrição, quem pediu e quando, quem aprovou ou recusou e quando, evidência (download registrado no log de acesso) e contestação vinculada. As divergências ficam na mesma aba.
- **Banco**: os pedidos nunca são removidos; o desfecho é gravado uma única vez (gatilho) e as linhas por componente são imutáveis.
- **Auditoria**: `title_amount.requested`, `adjusted`, `applied` (lançamento), `rejected`, `cancelled`, `divergence_detected`, `divergence_resolved`, `settings_updated`, `settings_proposed` e `settings_confirmed`.
- **Eventos de negócio** e **métricas**: [logs-e-metricas.md](../tecnico/logs-e-metricas.md).
- **Minhas pendências**:
  - "Correções de valor de título para aprovar" (`TITLE_AMOUNT_APPROVE`, sem os próprios pedidos);
  - "Aumento do limite de correção de valor para confirmar" (`TITLE_AMOUNT_APPROVE` com todos os credores, sem a própria proposta);
  - "Cargas do credor diferentes do valor corrigido" (`TITLE_AMOUNT_ADJUST`).
- **Telas**: botão "Corrigir valor" no detalhe do título; página `/titulos/correcoes-de-valor` (para aprovar, divergências e limite, com a proposta de aumento e o botão "Confirmar aumento" para outra pessoa). API: `GET`/`PUT /title-amount-adjustments/settings` e `POST /title-amount-adjustments/settings/confirm` (o corpo leva o valor proposto que a pessoa revisou; se a proposta mudou desde a leitura, 409 `title_amount.proposal_changed`).

## 7. Contestação procedente

Quando a contestação sugere **corrigir o valor**, o atalho abre a correção no título já com o motivo "Contestação procedente" e o vínculo com a contestação. A pendência de ajuste da contestação é concluída quando o saldo do título mudar, seja pela correção ou por uma carga do credor com o valor corrigido ([contestacoes.md §6](contestacoes.md#6-ajuste-sugerido-no-título)).
