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

### 2.1 Dados jurídicos do contrato (garantias, coobrigados, natureza do título)

Opcionais, por contrato (`docs/tecnico/desenho/juridico.md`, A.4). Exigem a coluna `contrato` — sem ela os dados são ignorados com aviso (`row.legal_fields_without_contract`). Quando o contrato tem vários títulos, repita os dados em cada linha ou informe-os em uma só: a gravação é idempotente. Dado jurídico inconsistente é **erro** (a linha não é gravada): carregar o título sem a garantia ou o coobrigado informado levaria a decisões de cobrança e jurídicas erradas; corrija e reenvie.

| Coluna | Conteúdo / formato | Problema → efeito |
| --- | --- | --- |
| natureza_titulo | `CONTRATO`, `CCB`, `NOTA_PROMISSORIA`, `DUPLICATA`, `CHEQUE`, `CONFISSAO_DIVIDA`, `OUTRO` (reclassificar recalcula a prescrição) | desconhecida → erro |
| titulo_executivo | `S`/`N` (exige `natureza_titulo`) | inválido → erro; sem natureza → aviso, ignorado |
| coobrigado_1_documento, coobrigado_1_nome, coobrigado_1_papel, coobrigado_1_desde (e `coobrigado_2_*`) | CPF/CNPJ e nome (pessoais); papel `AVALISTA`, `FIADOR`, `CODEVEDOR` ou `GARANTIDOR` (terceiro que deu bem em garantia); data de início | documento, nome e papel obrigatórios quando qualquer coluna do coobrigado é informada; documento inválido ou igual ao do devedor, papel desconhecido, data futura → erro |
| garantia_id | identificador da garantia no credor (obrigatório para bem `OUTRO`) | — |
| garantia_tipo | `ALIENACAO_FIDUCIARIA_VEICULO`, `ALIENACAO_FIDUCIARIA_IMOVEL`, `HIPOTECA`, `PENHOR`, `CESSAO_FIDUCIARIA_RECEBIVEIS`, `SEGURO_GARANTIA`, `OUTRA` | obrigatório com qualquer coluna `garantia_*`; desconhecido → erro |
| garantia_bem | `VEICULO`, `IMOVEL`, `OUTRO` (padrão pelo tipo: veículo para alienação de veículo; imóvel para alienação de imóvel e hipoteca; outro nos demais) | desconhecido → erro |
| garantia_descricao | texto (obrigatório para bem `OUTRO`) | — |
| garantia_placa, garantia_renavam, garantia_chassi, garantia_ano_fabricacao, garantia_ano_modelo | veículo: ao menos um identificador (pessoais para fins de mascaramento) | sem identificador, ano inválido, placa/RENAVAM/chassi inválidos → erro |
| garantia_matricula, garantia_cartorio, garantia_municipio, garantia_uf | imóvel: todos obrigatórios | ausente ou UF inválida → erro |
| garantia_gravame_orgao, garantia_gravame_numero, garantia_gravame_data | registro do gravame | data inválida → erro |
| avaliacao_valor, avaliacao_data, avaliacao_fonte | avaliação da garantia: valor > 0, data não futura, fonte `CREDOR` (padrão), `AVALIADOR`, `FIPE`, `MERCADO` ou `LEILAO`; gravada uma vez por lote e só se mudar | inválida → erro; sem garantia → aviso, ignorada |

A garantia é casada pelo `garantia_id` ou pelo bem (chassi, RENAVAM ou placa; matrícula + cartório + município + UF) e cobre todos os títulos do contrato. Na atualização mudam descrição, identificação e gravame; tipo e situação não mudam pela carga (aviso `collateral.type_mismatch`). Coobrigado com participação encerrada não é reaberto (aviso `obligation_party.ended`). Uma linha aceita até dois coobrigados e uma garantia; contratos com mais, em linhas adicionais do mesmo contrato.

**Datas:** `dd/mm/aaaa` (dia/mês com 1 ou 2 dígitos), `dd-mm-aaaa` ou `aaaa-mm-dd` (horário descartado); anos 1900–2199; ano com 2 dígitos é recusado (ambíguo).
**Valores:** vírgula ou ponto decimal, conforme a **coluna** (§7): `1.234,56`, `1234,56`, `1234.56`, `1,234.56`, `1 234,56`, `1234`, com ou sem `R$`, sinal `-` (no início ou no fim) ou parênteses `(1,50)`; até 2 casas decimais (casas a mais só se forem zeros). Recusados: notação científica (`1,23E+11`), outros símbolos de moeda, separadores repetidos ou fora do lugar (`1..5`, `12.34.567`). Célula **numérica** do XLSX e número JSON não têm ambiguidade; números guardados como texto passam pela análise da coluna.
**Identificadores:** `id_titulo`, `contrato`, `documento` e `garantia_id` em notação científica (`1,23457E+15`) ou numéricos com mais de 15 dígitos no XLSX (a planilha perdeu os últimos) são erro: formate essas colunas como texto.
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

## 5. Colunas a mais e evolução do layout

Colunas que o layout não usa são ignoradas, com um aviso único no lote (linha 0, `import.columns_ignored`). Colunas com prefixo jurídico (`garantia`, `avaliacao`, `coobrigado`, `natureza_titulo`, `titulo_executivo`) não reconhecidas — por exemplo `coobrigado_3_documento` ou uma segunda garantia — geram o aviso específico `import.columns_not_supported`. Campos novos de versões futuras serão sempre opcionais: arquivos desta versão continuarão válidos.

## 6. Processamento e limites

| Item | Padrão |
| --- | --- |
| Tamanho do arquivo | 50 MB |
| Linhas por lote | 1.000.000 (verificado antes de qualquer gravação) |
| Registros por requisição da API | 1.000 |
| XLSX | até 1.000 partes, 1 GB descompactado, tabela de textos até 100 MB, razão de compressão até 100:1 (proteção contra *zip bomb*) |
| Ocorrências gravadas por lote | 50.000 (além disso, apenas contadas) |

O envio responde imediatamente (HTTP 202) e o lote segue `RECEIVED → PROCESSING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED` (pré-visualização: `VALIDATING → VALIDATED`). O processamento lê o arquivo inteiro antes de gravar (estrutura, cabeçalho, limite de linhas), depois grava em blocos de 500 linhas com checkpoint: uma queda retoma do ponto em que parou, sem duplicar. O lote pode ser cancelado na fila (de imediato) ou em processamento (ao fim do bloco corrente; o que já foi gravado permanece e pode ser completado reenviando o arquivo).

**Pré-visualização** (`mode=VALIDATE_ONLY`): valida e gera o relatório sem gravar nada. Na tela, toda importação passa pela **conferência** (§8); pela API o lote validado ainda pode ser importado com `POST /batches/{id}/reprocess`.

**Integrações:** `POST /batches/records` aceita valores string, número ou booleano. Números JSON não têm ambiguidade (até 15 dígitos significativos; acima disso envie como texto). Strings passam pela mesma análise do CSV: coluna ambígua sem `numberFormat` (`{"valor_principal": "COMMA"}`) falha o lote antes de gravar (`import.number_format_undecided`); declaração que contradiz os valores falha com `import.number_format_conflict`. `controlTotals` (`{"rows": 1000, "principalTotal": 123456.78}`) é opcional: divergência acima de R$ 0,01 (ou de uma linha) falha o lote antes de gravar (`import.control_totals_mismatch`, indicando a razão 10ⁿ). No envio de arquivo direto (`POST /batches`, `mode=IMPORT`), `decimalSeparator=COMMA|DOT` resolve colunas ambíguas.

**Relatório de erros:** `GET /api/v1/ingestion/batches/{id}/issues/report` — CSV (UTF-8 com BOM, `;`) com linha, severidade, campo, código, mensagem e trecho do valor **mascarado**; células que começam com `= + - @` recebem apóstrofo inicial. O download fica registrado no log de acesso.

## 7. Formato dos valores (vírgula ou ponto decimal)

O separador decimal é decidido **por coluna**, olhando todas as linhas — nunca valor a valor:

| O que a coluna contém | Exemplo | Resultado |
| --- | --- | --- |
| Só valores numéricos da planilha ou sem separador | `1234` | nada a decidir |
| Valores que provam a vírgula decimal | `1.234,56`, `10,5`, `1.000.000` | vírgula decimal; `1.000` = mil |
| Valores que provam o ponto decimal | `1,234.56`, `10.5`, `1.00` | ponto decimal; `1.00` = um real (nunca vira 100) |
| Só valores ambíguos | `1.000`, `2.500`, `1,000` | **você escolhe** a leitura |
| As duas convenções na mesma coluna | `1.234,56` e `1,234.56` | **você escolhe** a leitura (inclusive "cada valor pelo seu formato") |

`1.000` é ambíguo porque pode ser mil (ponto de milhar) ou um real com três casas. Quando a coluna não prova a convenção, a conferência mostra os mesmos exemplos do arquivo convertidos em cada leitura (`1.000 → R$ 1.000,00` × `1.000 → R$ 1,00`) e a soma da coluna em cada uma.

## 8. Conferência da importação — passo a passo

Em **Importação de carteiras → Nova importação**. Nada é gravado na carteira até o último passo; você pode sair e voltar (o passo fica salvo).

1. **Arquivo** — escolha a carteira, o layout e o arquivo.
2. **Formato** — confira como os valores foram lidos, coluna por coluna, com exemplos reais. Coluna ambígua ou misturada: escolha a leitura (a validação só começa depois). Mudar a leitura depois refaz a prévia do mesmo arquivo.
3. **Linhas** — erros e avisos por linha, como na prévia. Linhas com erro não são importadas.
4. **Totais** — some na sua planilha e informe: quantidade de linhas (sem o cabeçalho), soma do `valor_principal`, do `saldo` e/ou do `valor_contrato` (a coluna como está, com repetições). Os totais do arquivo só aparecem depois que você informa os seus. Diferença acima de R$ 0,01 (ou de uma linha) **bloqueia**; a tela diz se a soma está 10, 100 ou 1000 vezes maior ou menor (erro de separador decimal), se ela bate com outra leitura do passo 2, quais linhas conferir primeiro e mostra subtotais por faixa de atraso, situação, produto e validade.
5. **Alertas** — valores muito acima ou abaixo do ticket médio do credor (ou do próprio arquivo, sem histórico), valores que divididos ou multiplicados por 10/100/1000 ficariam normais (provável separador decimal), saldo acima do que encargos justificam, vencimentos distantes ou antigos demais, títulos do mesmo devedor com valores muito diferentes, parcelas que não conferem com o valor do contrato e centavos fora do padrão do credor. Para cada um: **Confirmo que está correto** ou **Excluir do lote**, por linha ou em lote, sempre com justificativa. Alertas de provável separador decimal precisam ser todos revisados; mais de 10 abertos bloqueiam.
6. **Importar** — resumo do impacto (linhas a importar, títulos novos e existentes, suspensos, soma) e a palavra **IMPORTAR**. Acima de R$ 1.000.000,00 de principal, outra pessoa com permissão de aprovação (nem quem enviou nem quem pediu) precisa aprovar.

Tudo o que foi confirmado — leitura escolhida, totais informados e calculados, alertas e justificativas, linhas excluídas — fica registrado na conferência e na auditoria. Linhas excluídas aparecem no relatório da importação (`row.excluded_by_review`).

## 9. Importação PDF inteligente

Para quando o credor não consegue extrair os dados em CSV ou XLSX e envia **PDF**. Em **Importação de carteiras →
Importação PDF inteligente**:

1. **Envio** — escolha a carteira e o PDF (até 20 MB, sem senha, sem JavaScript; até 200 páginas com texto ou 30
   digitalizadas). O mesmo PDF já em andamento ou aprovado na carteira é recusado e a tela abre o existente.
2. **Mapa** — o sistema descobre **onde** estão os dados: com o mapa já salvo do credor, se o layout casar (sem IA), ou
   com a proposta da IA a partir do texto do PDF com os números e e-mails mascarados. O mapa nunca contém valores.
3. **Validação** (tela intermediária) — o PDF com as regiões lidas em destaque (devedor, cabeçalho da tabela, totais),
   o mapa em linguagem simples ("Coluna 'Pendente' → saldo devedor do título"; "cada título ocupa 2 linhas"; "devedor
   lido no cabeçalho"), os títulos encontrados com os **mesmos erros e avisos da importação por planilha** e a
   conferência dos totais impressos no documento com a soma extraída. Clique no número de um título para ver de onde
   ele saiu. O mapa é editável: campo de cada coluna, coluna ignorada, linhas por título, coluna que inicia o título,
   início (rótulos do cabeçalho) e fim da tabela, devedor, totais e linhas a ignorar — ao aplicar, o sistema reaplica o
   mapa ao texto na hora. Títulos podem ser excluídos do lote, com justificativa.
4. **Concordo, importar** — gera o lote no **layout padrão** (§2) com os títulos não excluídos e abre a **conferência
   da importação** (§8): formato, linhas, grandes totais informados por você, alertas de ticket médio e de separador,
   IMPORTAR e, acima do limite, a aprovação de outra pessoa. "Salvar mapa para este credor" faz os próximos PDFs do mesmo
   layout serem lidos sem IA. "Descartar" encerra sem gravar nada.

Regras:

- **Todo valor importado sai do texto do PDF.** A IA só descreve onde procurar; um programa aplica o mapa e cada valor é
  conferido de novo contra o texto da região de origem. PDF **digitalizado** (só imagem) é a exceção: a IA lê as páginas
  visualmente, cada título traz a confiança da leitura, os de confiança baixa precisam ser confirmados ou excluídos um a
  um e a aprovação exige declarar que os valores foram conferidos com as imagens.
- **Totais do documento que não conferem bloqueiam** até o mapa ser corrigido ou a divergência ser justificada (fica na
  auditoria). Sem totais no documento, confira com cuidado a soma na conferência de grandes totais.
- Valores com vírgula ou ponto decimal conforme o mapa (padrão: vírgula, `1.234,56`). Quem aprovou o mapa, a revisão
  aprovada e cada edição ficam registrados na auditoria.
- Pela API pública (`POST /public-api/v1/imports/pdf`) o PDF entra no mesmo fluxo e fica aguardando a validação de uma
  pessoa da assessoria: a API nunca importa um PDF sozinha.

### 9.1 Mapeamento sugerido: "Histórico Financeiro por Cliente"

Layout de relatório de ERP com cabeçalho do devedor, títulos em **duas linhas** (a 2ª é a conta contábil), linha
"Totais:" e quadro de resumo.

| No PDF | Vai para | Observação |
| --- | --- | --- |
| `Cliente:` (código + nome) | `nome` (sem o código inicial) e `info:codigo_cliente` (só o código) | abre o bloco de cada devedor |
| `CPF/CNPJ:` | `documento` | dígito verificador conferido |
| `Endereço:`, `Bairro:`, `Cidade:` | `endereco_logradouro`, `endereco_bairro`, `endereco_cidade` | o número vem junto do logradouro |
| `Telefone:`, `Email:` | `telefone_1`, `email_1` | |
| `Data Emissão:` (topo do relatório) | `data_saldo` | data-base do saldo, vale para todos os títulos |
| `Emp` + `Número` + `Lt` | `id_titulo` (unidos com "-", ex.: `1-32633-01`) | o número se repete entre parcelas; a parcela tem letras (`A3`), por isso não vai para `parcela` |
| `Número` | `contrato` | |
| `Vencto` | `vencimento` | coluna que inicia cada título |
| `Valor` | `valor_principal` | valor **original** do título |
| `Pendente` | `saldo` | **saldo** devedor informado pelo credor (conferência com a plataforma) |
| `Pagto` | `pago_se_preenchido` | preenchida = título informado como pago (não cria título; suspende o existente para conciliação) |
| `Emissão`, `Vcto Ori`, `Atraso` | `info:emissao`, `info:vencimento_original`, `info:dias_atraso` | informação adicional |
| `Jur Mora`, `Jur Boleto`, `Vlr+Jur+Bol` | `info:juros_mora`, `info:juros_boleto`, `info:valor_com_encargos` | encargos do credor **não** substituem a política de cálculo da plataforma |
| `Avul`, `Forma Pgto`, `Nosso Número`, `NFSe` | `info:avulso`, `info:forma_pagamento`, `info:nosso_numero`, `info:nfse` | informação adicional |
| 2ª linha: `Conta` | `info:conta` | |
| `Totais:` nas colunas `Valor` e `Pendente` | totais de controle de `valor_principal` e `saldo` (por devedor) | o quadro "Nº de Títulos" do resumo **não** é usado: conta títulos que não estão na lista (pagos, a vencer) |
| `Página` | linha ignorada | rodapé |

Informação adicional (`info:*`) é lida do PDF e mostrada na tela de validação enquanto o PDF estiver guardado (90 dias
depois de encerrada a importação); o layout canônico não tem campo livre, então ela não vai para o título (ver decisões
669 e 670).
