# Layout de importação de carteiras — versão 1

Especificação do **layout canônico** (código `PADRAO`, versão 1) usado pelo módulo `ingestion` para carregar devedores, contratos, títulos e saldos (M02/F04). Credores com arquivo próprio cadastram um **layout do credor**, que só muda os nomes/posições das colunas: os campos, formatos e regras abaixo valem para todos.

- Modelo vazio para download: `GET /api/v1/ingestion/layouts/standard/template?format=csv` (ou `xlsx`).
- Descrição para sistemas: `GET /api/v1/ingestion/layouts/standard`.

## 1. Formatos aceitos

| Formato | Regras |
| --- | --- |
| CSV | UTF-8 (com ou sem BOM), separado por `;`, aspas duplas para campos com `;` ou quebra de linha. Layouts de credor aceitam `;` `,` `\|` ou tabulação e as codificações UTF-8, ISO-8859-1 ou WINDOWS-1252. Caractere inválido para a codificação recusa o arquivo (nunca há substituição silenciosa). |
| XLSX | Primeira aba (ou a aba definida no layout do credor); primeira linha não vazia = cabeçalho. Fórmulas não são avaliadas: vale o último valor calculado salvo na planilha. Formate as colunas de documento como **texto** (o modelo XLSX já vem assim). |
| API | `POST /api/v1/ingestion/batches/records` com cabeçalho `Idempotency-Key` e corpo `{"portfolioId": "...", "mode": "IMPORT", "records": [{"documento": "...", ...}]}` — as chaves são os nomes de coluna abaixo; até 1.000 registros por requisição. |

Cabeçalhos são comparados sem diferenciar maiúsculas, acentos e espaços nas pontas ou repetidos (`Data_Saldo ` = `data_saldo`); o sublinhado faz parte do nome. Linhas totalmente vazias são ignoradas. O número de linha nos relatórios é o do arquivo (cabeçalho = linha 1).

## 2. Colunas

Obrigatórias em **negrito**. "Pessoal" indica dado do devedor: no relatório de erros o valor aparece mascarado.

| Coluna | Conteúdo | Formato | Problema → efeito |
| --- | --- | --- | --- |
| **documento** | CPF ou CNPJ do devedor (pessoal) | com ou sem máscara; zeros à esquerda perdidos pela planilha são recompostos (aviso `row.document_padded`) | inválido → erro |
| **nome** | nome/razão social (pessoal) | até 200 caracteres | ausente, longo ou com fórmula → erro |
| tipo_pessoa | `F`/`PF`/`FISICA` ou `J`/`PJ`/`JURIDICA` | — | não confere com o documento → erro |
| data_nascimento | (pessoal) | data | inválida/futura → aviso, ignorada |
| telefone_1..3 | (pessoal) | qualquer máscara brasileira ou `+55…` | inválido → aviso, contato ignorado |
| whatsapp_1..3 | telefone correspondente tem WhatsApp | `S`/`N`, `Sim`/`Não`, `1`/`0`, `X` | inválido → aviso, considerado `N` |
| email_1..2 | (pessoal) | — | inválido → aviso, contato ignorado |
| endereco_logradouro, endereco_numero, endereco_complemento, endereco_bairro, endereco_cidade, endereco_uf, endereco_cep | endereço (pessoal) | UF com 2 letras; CEP com 8 dígitos | sem logradouro → aviso, endereço ignorado; UF/CEP inválidos → aviso, parte ignorada |
| contrato | número do contrato de origem | até 80 caracteres | dados de contrato sem número → aviso, ignorados |
| produto | produto do contrato | até 120 caracteres | longo ou com fórmula → erro |
| data_contrato | assinatura do contrato | data | inválida → erro |
| valor_contrato | valor original do contrato | valor | inválido/negativo → erro |
| **id_titulo** | identificador do título no credor — **chave da reimportação** | até 80 caracteres | ausente, longo, repetido no arquivo → erro |
| parcela | número da parcela | inteiro positivo | inválido → erro |
| **vencimento** | vencimento do título | data | inválido → erro |
| **valor_principal** | valor principal | valor > 0 | inválido, zero ou negativo → erro |
| saldo | saldo informado pelo credor (conciliação) | valor ≥ 0 | inválido → aviso, ignorado |
| data_saldo | data-base do saldo | data; obrigatória com `saldo` | ausente/inválida → aviso, saldo ignorado |
| situacao | situação do título no credor (§4) | `ATIVO`, `PAGO`, `SUSPENSO`, `CONTESTADO`, `CANCELADO` (aceita `EM ABERTO`, `QUITADO`, `LIQUIDADO`, `EM CONTESTACAO`, `BAIXADO`) | desconhecida → erro |

**Datas:** `dd/mm/aaaa` (dia/mês com 1 ou 2 dígitos), `dd-mm-aaaa` ou `aaaa-mm-dd` (horário descartado); anos 1900–2199; ano com 2 dígitos é recusado (ambíguo).
**Valores:** `1.234,56`, `1234,56`, `1234.56`, `1234`, com ou sem `R$`; até 2 casas decimais. `1,234.56` (padrão americano) é recusado por ambiguidade.
**Fórmulas:** texto iniciado por `=` ou `@` em nome, identificadores, produto e endereço é recusado (proteção contra *CSV/XLSX injection*).

Severidade: **erro** — a linha não é gravada; **aviso** — a linha é gravada e o dado apontado é ignorado ou ajustado. Um título com erro não impede as demais linhas.

## 3. Reimportação idempotente

- O devedor é casado pelo documento; o título por (credor, `id_titulo`); o contrato por (credor, `contrato`).
- Reenviar o mesmo arquivo, ou o arquivo corrigido, **não duplica** devedores, contatos, títulos, lançamentos ou saldos: o que não mudou fica inalterado, o principal alterado é corrigido por lançamento de ajuste e contatos novos são acrescentados.
- O mesmo conteúdo (SHA-256) já importado com sucesso na carteira é recusado (`import.duplicate_file`), salvo confirmação explícita com motivo (auditada). O mesmo conteúdo em importação simultânea na carteira é sempre recusado.
- Títulos encerrados (quitados, cancelados, devolvidos) não são alterados pela carga (aviso `title.locked_status`); mudança de carteira não acontece pela carga (aviso `title.portfolio_mismatch` — use a transferência de carteira).

## 4. Situação informada pelo credor (`situacao`)

A carga **nunca** dá baixa financeira nem encerra títulos — isso exige registro de pagamento ou cancelamento explícito — mas também não deixa em cobrança o que o credor diz estar pago, suspenso ou contestado.

| Situação | Título inexistente | Em cobrança | Já suspenso | Encerrado |
| --- | --- | --- | --- | --- |
| `ATIVO` / vazio | cria | atualiza | atualiza (não reativa) | não altera (aviso) |
| `SUSPENSO`, `CONTESTADO` | cria e suspende | atualiza e suspende | atualiza | não altera (aviso) |
| `PAGO`, `CANCELADO` | **não cria** (aviso `row.title_closed_not_found`) | não altera; **suspende** para conciliação (aviso `row.title_reported_paid` / `row.title_reported_cancelled`) | não altera (aviso) | não altera |

## 5. Colunas futuras e colunas a mais

Colunas que o layout não usa são ignoradas, com um aviso único no lote (linha 0, `import.columns_ignored`). Colunas com os prefixos `garantia`, `avaliacao`, `coobrigado`, `natureza_titulo` e `titulo_executivo` estão **reservadas** para a versão 2 do layout (garantias, avaliações, coobrigados e natureza do título — `docs/design/juridico.md`, A.4) e hoje geram o aviso `import.columns_not_supported`. Campos novos serão sempre opcionais: arquivos da versão 1 continuarão válidos.

## 6. Processamento e limites

| Item | Padrão |
| --- | --- |
| Tamanho do arquivo | 50 MB |
| Linhas por lote | 1.000.000 (verificado antes de qualquer gravação) |
| Registros por requisição da API | 1.000 |
| XLSX | até 1.000 partes, 1 GB descompactado, tabela de textos até 100 MB, razão de compressão até 100:1 (proteção contra *zip bomb*) |
| Ocorrências gravadas por lote | 50.000 (além disso, apenas contadas) |

O envio responde imediatamente (HTTP 202) e o lote segue `RECEIVED → PROCESSING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED` (pré-visualização: `VALIDATING → VALIDATED`). O processamento lê o arquivo inteiro antes de gravar (estrutura, cabeçalho, limite de linhas), depois grava em blocos de 500 linhas com checkpoint: uma queda retoma do ponto em que parou, sem duplicar. O lote pode ser cancelado na fila (de imediato) ou em processamento (ao fim do bloco corrente; o que já foi gravado permanece e pode ser completado reenviando o arquivo).

**Pré-visualização** (`mode=VALIDATE_ONLY`): valida e gera o relatório sem gravar nada; o lote validado pode ser importado depois com `POST /batches/{id}/reprocess`.

**Relatório de erros:** `GET /api/v1/ingestion/batches/{id}/issues/report` — CSV (UTF-8 com BOM, `;`) com linha, severidade, campo, código, mensagem e trecho do valor **mascarado**; células que começam com `= + - @` recebem apóstrofo inicial. O download fica registrado no log de acesso.
