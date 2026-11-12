# Desenho — Histórico de acordos, ficha lateral compacta e situação jurídica como atenção especial (12/11/2026)

Três ajustes pedidos pelo responsável em 12/11/2026. Decisões 1218–1232 em
[decisoes-autonomas.md](../../negocio/decisoes-autonomas.md).

| # | Pedido | Hoje | Proposta |
| --- | --- | --- | --- |
| A1 | Aba com o histórico dos acordos (realizados e em andamento) e a situação de cada um: no caso, os acordos do caso; na ficha do devedor, o resumo de todos os acordos dele. Cada um com link para o detalhe | A aba "Negociação" do caso lista os acordos do caso junto com as propostas, sem resumo; a ficha do devedor não mostra acordos | Componente `app-agreement-history` na aba **Acordos** do caso (estação e consulta) e na ficha do devedor, com o resumo calculado no servidor e o link para o acordo **numa aba nova** |
| A2 | Listas da ficha do devedor no painel lateral da estação (contatos, títulos…) em tabela com barra de rolagem lateral | `app-data-table` só vira lista empilhada abaixo de 576 px **de tela**; no painel lateral (tela larga, painel de até 820 px) a tabela rola para o lado. No celular, as colunas além de três metadados somem | Lista empilhada também quando a tabela **não cabe** na área do painel, com **linha expansível** (acordeão) que mostra todos os campos |
| A3 | Recuperação judicial ou extrajudicial como "atenção especial", com marcação | A insolvência (cobrança B2B §7) aparece só como aviso amarelo; o registro é um link discreto na ficha do devedor, separado do "Marcar atenção especial" | A situação jurídica da empresa entra no selo e no diálogo de **atenção especial**: selo "Recuperação judicial" no cabeçalho e "Marcar atenção especial" oferece recuperação judicial, extrajudicial e falência para empresa |

## A1. Histórico de acordos

### Servidor (`negotiation`)

* `AgreementUseCases#summary(AgreementFilter)` → `AgreementSummaryView`, em
  `GET /api/v1/negotiation/agreements/summary?creditorId=&debtorId=&caseId=` (`AGREEMENT_VIEW`). Escopo por credor
  igual ao da busca: credor fora do escopo é recusado, e sem credor só os acordos dos credores do escopo entram. Devedor
  autenticado no portal fica preso ao próprio cadastro (`DebtorSelfScope`), como na busca.
* Uma consulta agregada (`group by status`) devolve, por situação, a quantidade, o total negociado e o total pago.
  O resumo traz:
  * `total`, `active` (formalizado ou em pagamento), `settled`, `broken`, `cancelled` e `renegotiated`;
  * `totalAmount` e `paidAmount` (soma de todos, inclusive os substituídos: o que foi pago num acordo renegociado foi
    pago de fato);
  * `fulfillmentRate` = quitados ÷ (quitados + quebrados), nulo sem acordo encerrado. É o critério de sucesso do
    projeto: acordo que o devedor cumpre. Os renegociados e os cancelados ficam de fora (o desfecho deles está no
    acordo seguinte ou não depende do devedor).
* Sem migration: o índice de `negotiation.agreement` por devedor e por caso já existe.

### Tela

* `app-agreement-history` (`features/negotiation/agreements/agreement-history.ts`), com escopo `caseId` ou `debtorId`:
  * **Resumo** em faixa compacta: acordos, em vigor, quitados, quebrados, cumprimento e total pago, com dica de como o
    cumprimento é calculado;
  * **Lista** (`app-data-table`, do mais recente para o mais antigo, 20 por página): data da formalização (link), credor
    e carteira (só na ficha do devedor), total e pago, parcelas ("3 de 6 pagas", vencidas ou a próxima), situação (com
    "renegociação N" e o motivo do encerramento na dica).
  * O link abre o acordo **numa aba nova** (`target="_blank"`, ícone e "abre em nova aba" para o leitor de tela). Na
    estação o atendimento não é interrompido; na ficha, o contexto não se perde. O detalhe do acordo continua sendo a
    tela própria `/negociacao/acordos/:id`, com cronograma, pagamentos, termo e ações.
  * Mais de 20 acordos: paginação da própria tabela. "Ver na lista de acordos" leva a `/negociacao/acordos?devedor=`
    (ou `?devedor=&credor=` no caso), com os filtros na URL.
  * Só com `AGREEMENT_VIEW`: sem a permissão, a aba não aparece.
* **Ficha do caso** (estação e consulta): aba nova **Acordos** (índice 7, `?aba=acordos`, `Alt + 8`), no menu "Mais".
  A aba "Negociação" passa a mostrar só os acordos **em vigor** (com o Pix da parcela) e um atalho "Histórico de
  acordos (N)" para a aba nova.
* **Ficha do devedor**: aba **Acordos** depois de "Interações", com o resumo de todos os acordos do devedor nos credores
  do escopo e a coluna do credor. Aparece também na ficha embutida no painel da estação.

## A2. Ficha lateral sem rolagem lateral

* `app-data-table` ganha o modo **ajuste à área** (`fit`): além da tela abaixo de 576 px, a tabela vira lista empilhada
  quando não cabe na largura da própria área (mede o transbordo depois de cada renderização e com `ResizeObserver`;
  volta a ser tabela quando a área chega à largura medida). Um limite fixo não serviria: o painel tem até 820 px e a
  tabela de contatos não cabe nele. O modo vale onde a tela declarar `appCompactTables` (diretiva em
  `shared/components/data-table/compact-tables.ts`, token `DATA_TABLE_STACK`). A estação declara na ficha do devedor do
  painel lateral; as demais telas não mudam.
* **Linha expansível** na lista empilhada (vale também para o celular): a linha mostra o resumo (título, selo, até três
  metadados e ações) e, quando há outras colunas (inclusive as marcadas `mobile: 'hidden'`), um botão "Mais detalhes"
  (`aria-expanded`, `aria-controls`) abre os demais campos com rótulo, na mesma grade de definições. Nada se perde: o
  que a tabela mostra, a linha mostra.
* As colunas das tabelas da ficha já declaram os papéis (`mobile`). Títulos: título, situação, vencimento, atraso e
  saldo no resumo; credor, carteira e valor original no detalhe. Contatos: valor (com o selo "Novo" e a pessoa de
  contato), situação, tipo, classificação e origem; prioridade no detalhe.
* Demais abas da ficha (interações, contratos de origem, garantias, razão, titularidade, restrições, representantes e
  acordos) usam `app-data-table` e ganham o mesmo comportamento sem mudança própria.

## A3. Situação jurídica da empresa como atenção especial

O processo continua sendo o **processo de insolvência** do módulo `debt` (cobrança B2B §7): tem número CNJ, vara,
data do pedido, convolação e encerramento, vale para matriz e filiais e suspende a cobrança. O marcador de
vulnerabilidade (conformidade §1) é outra coisa: dado pessoal com base legal (LGPD), que pede atendimento cuidadoso mas
não suspende a cobrança. Juntar os dois num só registro misturaria regras e bases legais. A mudança é de **entrada e
apresentação**:

* `app-vulnerability-badge` recebe `personType`. Para empresa, consulta também a insolvência (silenciosa):
  * processo em curso → selo **"Recuperação judicial"** (ou extrajudicial, ou falência) no cabeçalho, ao lado dos
    demais selos de atenção especial; o clique abre o diálogo de atenção especial;
  * "Marcar atenção especial" aparece para quem registra vulnerabilidade **ou** para quem pode registrar o processo
    (`DEBTOR_MANAGE` e escopo sobre todos os credores da empresa, o `canManage` da consulta).
* Diálogo de atenção especial, para empresa:
  * "Situação atual" mostra o processo em curso (tipo, desde quando, número) e a suspensão da cobrança;
  * o campo "Situação" lista **Recuperação judicial**, **Recuperação extrajudicial** e **Falência** (grupo "Situação
    jurídica da empresa") ao lado de "Outra situação". Escolhida uma situação jurídica, base legal e origem saem do
    formulário. "Continuar" abre o formulário do processo (número CNJ, data do pedido, vara e origem da informação),
    o mesmo do aviso;
  * com processo em curso, o grupo jurídico não é oferecido (convolar e encerrar continuam no aviso da ficha).
* O formulário do processo sai do aviso para o serviço `InsolvencyActions` (`features/debt/data`), usado pelo aviso e
  pelo diálogo.
* Na estação o selo também oferece o registro (para quem tem a permissão): é comum o representante contar no
  atendimento que a empresa entrou em recuperação.

## Testes

* `AgreementSummaryTest` (domínio do resumo: contagem, cumprimento e nulo sem encerrados) e o resumo no
  `NegotiationAgreementIT` (por devedor e por caso, com renegociação, e escopo por credor, contra PostgreSQL).
* Frontend: `agreement-history.spec.ts`, `data-table.spec.ts` (ajuste à área e linha expansível),
  `vulnerability-dialog.spec.ts` (situações jurídicas só para empresa e quem registra o processo),
  `case-negotiation.spec.ts` (só acordos em vigor e atalho do histórico) e `collection-station.spec.ts` (`Alt + 8`).
