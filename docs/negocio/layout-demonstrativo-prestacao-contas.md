# Demonstrativo de prestação de contas ao credor (layout e regras)

O demonstrativo mostra ao credor, por período, **o que foi recebido** dos devedores dele, **quanto a assessoria fica** (comissão, valores fixos por acordo e honorários cobrados do devedor), as **custas reembolsáveis** e o **valor líquido a repassar**. É gerado em **Prestação de contas → Demonstrativos**, só é enviado depois de **aprovado por uma pessoa diferente de quem o calculou**, e não muda depois de aprovado: estornos e correções posteriores aparecem no demonstrativo seguinte.

Cada demonstrativo aprovado gera três arquivos, todos guardados com a impressão digital (SHA-256) que o credor pode conferir:

- **CSV** (`;`, UTF-8) e **XLSX** com o mesmo conteúdo — um registro por recebimento, estorno, detalhe, despesa, ajuste e total, para o credor conciliar com os próprios registros;
- **PDF resumo** com os totais por bloco, as observações do período e o SHA-256 do arquivo conciliável.

## O que entra no período

| Entra | Não entra (aparece só como informação) |
| --- | --- |
| Pagamentos confirmados e aplicados a títulos ou parcelas de acordo, com data de pagamento até o fim do período e ainda não prestados (inclusive de períodos anteriores que chegaram depois) | Valor negociado em acordos (acordo não pago não é recuperação) |
| Pagamentos recebidos na conta da assessoria **já conciliados com o extrato** (quando o contrato exige) | Pagamentos ainda não conciliados — entram no primeiro demonstrativo depois da conciliação |
| Pagamentos feitos diretamente ao credor (informados pela assessoria) | Comissão prevista sobre acordos em aberto |
| Estornos e devoluções, na data do estorno | Pagamentos sem regra contratual vigente (até a regra ser cadastrada) |
| Custas reembolsáveis adiantadas pela assessoria ou por escritório parceiro | Custas pagas pelo próprio credor (listadas sem efeito no valor) |
| Ajustes aprovados, sempre com motivo | Divergências de conciliação em aberto (quantidade destacada) |

## Como o valor líquido é calculado

Para cada recebimento:

```
honorários da assessoria = honorários cobrados do devedor × fração contratual da assessoria
base de comissão         = valor recebido − honorários da assessoria
comissão                 = base × percentual da regra do contrato (por carteira, faixa de atraso, à vista × parcelado…)
valor fixo               = valor por acordo, no primeiro pagamento do acordo
efeito no repasse        = valor recebido pela assessoria − honorários da assessoria − comissão − valor fixo
```

**Valor líquido a repassar** = soma dos efeitos − custas reembolsáveis + ajustes. Se o resultado for negativo (por exemplo, quando o devedor pagou diretamente ao credor), é o **valor que o credor deve à assessoria**.

Exemplo: pagamento de R$ 1.100,00 numa parcela de acordo, dos quais R$ 100,00 são honorários da assessoria, contrato com 12% para parcelado acima de 90 dias e R$ 25,00 por acordo → base R$ 1.000,00, comissão R$ 120,00, efeito no repasse **R$ 855,00**.

## Arquivo conciliável (CSV / XLSX)

Datas `AAAA-MM-DD`, valores com ponto decimal e duas casas, negativos com `-`. Colunas (sempre todas, vazias quando não se aplicam):

| # | Coluna | Conteúdo |
| --- | --- | --- |
| 1 | `tipo_registro` | `RECEBIMENTO`, `ESTORNO`, `DETALHE`, `DESPESA`, `AJUSTE`, `TOTAL` |
| 2–5 | `demonstrativo_id`, `credor_id`, `periodo_inicio`, `periodo_fim` | identificação do demonstrativo |
| 6 | `chave` | id do recebimento/despesa/ajuste; no `TOTAL`, o código do total |
| 7 | `alocacao_id` | identificador do pagamento aplicado (o mesmo das baixas no acordo/título) |
| 8 | `pagamento_id` | pagamento no sistema (Pix, boleto, depósito informado) |
| 9 | `acordo_id` | acordo, quando o pagamento foi de parcela |
| 10 | `parcela` | número(s) da parcela do acordo (`1`, `1\|2`) |
| 11 | `titulo_credor` | identificação do título no credor (`contrato/parcela`, como na carga); vários separados por `\|` |
| 12 | `componente` | no `DETALHE`: `INSTALLMENT` (parcela) ou componente do título (`PRINCIPAL`, `INTEREST`, `FINE`, `FEES`, `HONORARIUM`); na `DESPESA`: tipo da custa; no `AJUSTE`: categoria |
| 13–14 | `data_pagamento`, `data_competencia` | data do pagamento; data em que entrou na prestação de contas (estorno: data do estorno) |
| 15 | `recebido_por` | `AGENCY` (conta da assessoria) ou `CREDITOR` (pago direto ao credor); na `DESPESA`, quem adiantou |
| 16–18 | `meio`, `tipo_recebimento`, `dias_atraso` | meio do pagamento; `LUMP_SUM` (à vista) ou `INSTALLMENTS` (parcelado); atraso considerado na regra |
| 19 | `valor` | valor recebido/estornado; no `DETALHE`, a parte da parcela/componente; no `TOTAL`, o total |
| 20–21 | `honorarios_assessoria`, `honorarios_credor` | divisão dos honorários cobrados do devedor |
| 22–25 | `base_comissao`, `percentual`, `comissao`, `valor_fixo` | memória da comissão |
| 26 | `efeito_repasse` | efeito do registro no valor líquido |
| 27–28 | `regra`, `versao_regra` | regra do contrato aplicada e sua versão |
| 29 | `referencia` | estorno: recebimento estornado; despesa: id da custa no jurídico; ajuste: demonstrativo corrigido |
| 30 | `observacao` | motivo do estorno, natureza da despesa ou motivo do ajuste |

Totais de controle (`TOTAL`): `QTD_RECEBIMENTOS_E_ESTORNOS`, `RECEBIDO_ASSESSORIA`, `RECEBIDO_CREDOR`, `ESTORNOS`, `RECUPERADO_LIQUIDO`, `CONCILIADO`, `HONORARIOS_ASSESSORIA`, `HONORARIOS_CREDOR`, `COMISSAO`, `VALOR_FIXO`, `SOMA_EFEITO_REPASSE_RECEBIMENTOS`, `DESPESAS_REEMBOLSAVEIS`, `AJUSTES`, `LIQUIDO_A_REPASSAR`. Conferência: `SOMA_EFEITO_REPASSE_RECEBIMENTOS − DESPESAS_REEMBOLSAVEIS + AJUSTES = LIQUIDO_A_REPASSAR`.

Textos que começam com `=`, `+`, `-` ou `@` recebem um apóstrofo inicial (proteção contra fórmulas em planilhas). Nenhum dado pessoal do devedor (nome, documento, contato) vai no arquivo: a conciliação é pelas chaves do título e do pagamento.

## Ciclo do demonstrativo

1. **Rascunho** do período (sugerido pelo dia de fechamento do contrato; um por vez por credor, sem lacunas entre períodos).
2. **Calculado** pelo financeiro, depois do fim do período; pode ser recalculado até a aprovação.
3. **Ajustes** com motivo; acima de R$ 1.000,00 precisam da aprovação de outra pessoa.
4. **Revisado** e **aprovado** pelo gestor (nunca por quem calculou ou pediu ajuste). A partir daqui não muda.
5. **Enviado** ao credor com os arquivos; o credor **aceita** ou **contesta** (registrado pela assessoria). A contestação é resolvida com ajuste no demonstrativo seguinte.
6. **Repasse** registrado com o comprovante bancário; o saldo a repassar por credor fica disponível até zerar.
