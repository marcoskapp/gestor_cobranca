# Layout de extrato / arquivo de retorno para conciliação (CSV genérico)

Usado em **Pagamentos → Conciliação → Importar extrato** (formato `CSV_GENERIC`). Cada linha é uma movimentação da conta de recebimento; o sistema concilia automaticamente com instruções Pix, pagamentos e parcelas e manda o restante para a fila de divergências. Importar de novo um extrato que se sobrepõe a outro não gera baixa em dobro.

## Formato

- Codificação **UTF-8**, separador **`;`**, primeira linha com o cabeçalho (nomes sem diferença entre maiúsculas e minúsculas, em qualquer ordem).
- Até 10 MB e 50.000 linhas por arquivo. O mesmo arquivo (idêntico) só pode ser importado uma vez.

| Coluna | Obrigatória | Conteúdo |
| --- | --- | --- |
| `data` | sim | data do crédito: `AAAA-MM-DD` ou `DD/MM/AAAA` |
| `valor` | sim | valor positivo, vírgula ou ponto decimal, sem separador de milhar (`1234,56`) |
| `tipo` | não | `C` (crédito, padrão) ou `D` (débito — ignorado) |
| `id_transacao` | não | identificador da transação no banco/PSP (`endToEndId` do Pix, número do documento) |
| `referencia` | não | `txid` do Pix, id da instrução, do pagamento ou da parcela do acordo |
| `descricao` | não | histórico do banco (até 500 caracteres; não pode começar com `=`, `+`, `-` ou `@`) |

Identificadores aceitam letras, números e `- _ . : /` (até 100 caracteres).

## Como cada linha é tratada

1. Débito → ignorado. Mesma transação já importada (mesmo `id_transacao`, ou mesma data, valor, referência e ordem de repetição) → ignorada.
2. `id_transacao` igual ao de um pagamento já registrado (Pix recebido ou referência de pagamento informado) → **conciliado**.
3. `referencia` com o `txid`/id de uma instrução ainda sem pagamento → o extrato **confirma o pagamento**, que é baixado no acordo/título e conciliado.
4. `referencia` com id de pagamento ou de parcela (pagamento confirmado com mesmo valor, data em até 3 dias) → **conciliado**.
5. Valor diferente do esperado, pagamento ainda não confirmado ou crédito sem correspondência → **divergência** para análise (vincular a um pagamento, aceitar ou descartar, sempre com justificativa). Quem informou um pagamento manual não pode conciliá-lo.

## Exemplo

```
data;valor;tipo;id_transacao;referencia;descricao
2026-10-20;171,05;C;E00000000202610201200abcdefghij1;9f1c0a2b3c4d5e6f708192a3b4c5d6e7;PIX RECEBIDO
20/10/2026;100,00;C;DOC-123;;TED RECEBIDA
2026-10-20;10,00;D;;;TARIFA
```

Formatos CNAB 240/400 de bancos serão aceitos quando o provedor de boleto for definido.
