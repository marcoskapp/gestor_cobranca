# API pública de integração dos credores

Para o responsável pelo produto, a operação da assessoria e as equipes técnicas dos credores. Desenho técnico: [`tecnico/desenho/api-publica.md`](../tecnico/desenho/api-publica.md). Referência navegável: `/public-api/docs` no endereço da plataforma; versões e mudanças: `/public-api/docs/changelog`.

## 1. Para que serve

O credor passa a administrar a própria carteira na assessoria **de sistema para sistema**, sem planilhas por e-mail:

- enviar carteiras e títulos (JSON ou arquivo CSV/XLSX) e acompanhar o processamento, com o relatório de erros;
- consultar e sincronizar títulos, casos de cobrança, acordos com parcelas e pagamentos;
- suspender, reativar, cancelar ou devolver títulos; criar, atualizar e mudar a situação de carteiras;
- incluir e invalidar contatos de devedores;
- informar pagamento recebido diretamente pelo credor (com comprovante) e abrir contestação.

As regras são **as mesmas das telas**: a importação passa pela mesma validação e é idempotente (reenviar não duplica), o pagamento informado passa pela mesma revisão por segunda pessoa acima do limite, a carteira segue o mesmo ciclo de vida.

## 2. Como o credor se conecta

1. A assessoria cria, em **API pública → Tokens de integração**, um token para cada sistema do credor: escolhe o credor, os escopos (o que o token pode fazer), o limite de chamadas por minuto e, de preferência, os endereços de rede do credor.
2. O token aparece **uma única vez**. A assessoria entrega ao credor por canal seguro; o credor guarda num cofre de segredos.
3. O sistema do credor envia o token em todas as chamadas: `Authorization: Bearer gck_…`.

Cada token **enxerga um único credor**. Dados de outros credores da assessoria não existem para ele.

## 3. Segurança

| Controle | Como funciona |
| --- | --- |
| Segredo exibido uma vez | A assessoria guarda só uma impressão irreversível (com chave do servidor). Ninguém consegue recuperar o token depois. |
| Escopos | Só o necessário; quem cria o token só concede o que o próprio perfil pode fazer. Nenhum escopo vê documento ou contato sem máscara. |
| Revogação | Imediata e definitiva, com motivo. A chamada seguinte já é recusada. |
| Rotação | Gera novo segredo e mantém o anterior por uma carência (até 24 h), para o credor trocar sem parar a integração. Em vazamento: sem carência. |
| Endereços permitidos | Opcional: só aceita chamadas das faixas de rede informadas. |
| Limite de chamadas | Por token, por minuto. Acima disso a chamada é recusada (429) com o tempo de espera. |
| Expiração | Opcional, na criação. |
| Rastreabilidade | Cada chamada vai ao log de acesso (token, credor, operação, resultado, volume, tempo, endereço de origem e identificador da requisição). Criação, rotação e revogação vão à trilha de auditoria. |
| LGPD | Documento e contatos do devedor sempre mascarados; busca de devedor por documento só no corpo da chamada; nada de observações internas ou dados de outros credores. |

## 4. Escopos disponíveis

| Escopo | Permite |
| --- | --- |
| `portfolios:read` / `portfolios:write` | consultar credor, contratos e carteiras / criar, atualizar e mudar situação de carteiras |
| `imports:read` / `imports:write` | acompanhar lotes e baixar ocorrências / enviar e cancelar lotes |
| `titles:read` / `titles:write` | consultar e sincronizar títulos / suspender, reativar, cancelar e devolver |
| `debtors:read` / `debtors:write` | consultar devedores e contatos (mascarados) / incluir e invalidar contatos |
| `collection:read` | consultar a situação da cobrança (casos) |
| `agreements:read` | consultar acordos e parcelas |
| `payments:read` / `payments:write` | consultar pagamentos / informar pagamento recebido pelo credor |
| `disputes:read` / `disputes:write` | consultar / abrir contestações |

## 5. Consumo e faturamento

Toda chamada é medida: quantidade, dados recebidos e enviados (tamanho real do conteúdo) e registros processados (títulos importados, contatos e pagamentos gravados…), por token, credor e operação. As telas **Consumo da API** (gráficos por dia, por operação e por token, com exportação) e **Extratos da API** mostram esses números.

O modelo de faturamento está pronto, sem cobrança automática:

- **Preço no contrato do credor**: a mensalidade, as franquias e o preço do excedente por chamada, por MB recebido, por MB enviado e por registro ficam no **contrato de prestação de serviço** do credor, junto com o **dia de fechamento do consumo** (1 a 31). São editados na ficha do credor (contratos → integrações). Cada alteração fica guardada como uma nova versão e **vale a partir do próximo ciclo**: o ciclo em curso não muda.
- **Ciclo de consumo**: fecha no fim do dia de fechamento e o próximo começa às 00:00 do dia seguinte (horário de Brasília). Em meses sem o dia escolhido (por exemplo, dia 31 em fevereiro), o ciclo fecha no último dia do mês. Sem condições no contrato, o ciclo fecha no último dia do mês e o extrato mostra o consumo com valor zero.
- **Só se cobra o que deu certo**: chamadas bem-sucedidas (respostas 2xx/3xx). Não são cobradas as recusadas por excesso (429), os erros do credor (4xx) nem as falhas da plataforma (5xx) — nem os dados trafegados nessas chamadas.
- **Dados em MB inteiros**: 1 MB = 1.048.576 bytes. A conta é feita no total do ciclo de cada token, separadamente para dados recebidos e enviados, e arredondada para cima: 1 byte vira 1 MB; 1.048.576 bytes, 1 MB; 1.048.577 bytes, 2 MB. O total do credor é a soma dos tokens, e o extrato mostra cada token.
- **Mudança do dia de fechamento**: vale a partir do próximo ciclo e gera um ciclo de transição, mais curto que o normal. Nele a mensalidade e as franquias são **proporcionais aos dias**: dias do ciclo ÷ dias de um ciclo normal que terminasse na mesma data. Exemplo: o contrato fechava no dia 10 e passa a fechar no dia 20; o ciclo de 11/03 a 20/03 tem 10 dias de 28 — mensalidade de R$ 100,00 vira R$ 35,71 e franquia de 1.000 chamadas vira 358 (arredondada para cima). O extrato mostra os dias e o fator.
- **Extrato por ciclo**: os ciclos encerrados fecham sozinhos na madrugada seguinte, com o detalhamento por token e por operação. Recalcular sobre o mesmo consumo dá sempre o mesmo valor (o extrato guarda uma marca do consumo usado). Um novo fechamento do mesmo ciclo exige motivo e gera uma revisão; a anterior fica guardada. Ciclo sem consumo gera extrato só se o contrato tiver mensalidade.

## 6. Comportamentos importantes para o credor

- **Importação assíncrona**: o envio responde na hora com o lote; o processamento é acompanhado pelo lote. Repetir o envio com a mesma `Idempotency-Key` devolve o mesmo lote.
- **Sincronização incremental**: as listas vêm em ordem de atualização com um cursor; o credor guarda o cursor e continua dele na próxima sincronização. Alterações do último minuto aparecem na chamada seguinte.
- **Alteração concorrente**: carteiras e títulos têm versão (`ETag`). Se o credor enviar a versão lida (`If-Match`) e alguém tiver alterado antes, a alteração é recusada (412) para não sobrescrever.
- **Baixa de título**: não há "baixar" direto — o credor informa o pagamento com comprovante (regra de baixa com evidência).

## 7. Versões e compatibilidade

A versão faz parte do endereço (`/public-api/v1`). **Até a produção tudo fica na versão 1.0**; o controle de versões passa a valer quando o responsável avisar.

**Mudanças compatíveis** (entram na versão vigente, registradas no changelog):

- nova operação, novo escopo, novo campo **opcional** na entrada;
- novo campo na resposta (o sistema do credor deve ignorar campos que não conhece);
- novo valor em campos de situação documentados como extensíveis, novo código de erro dentro do mesmo status;
- limites mais generosos, mensagens de erro mais claras.

**Mudanças incompatíveis** (só numa nova versão, `v2`):

- remover ou renomear operação, campo ou escopo; tornar obrigatório um campo opcional;
- mudar tipo, formato ou significado de um campo; mudar status HTTP de um caso existente;
- restringir validações já aceitas; mudar a ordem ou a semântica da paginação.

Quando houver uma nova versão, a anterior fica **depreciada** e continua funcionando até a data de desligamento anunciada: as respostas trazem `Deprecation` e `Sunset`, e a página de changelog mostra o prazo e o guia de migração. Depois do desligamento, a versão antiga responde 410.

## 8. O que ainda depende do responsável

- Proteção de borda e limite global de chamadas entre servidores (AWS WAF ou API Gateway).
- Endereço público da documentação para os credores.
- Valores das condições de cobrança em cada contrato e forma de faturar.
- Avisos automáticos para o sistema do credor (webhooks: lote processado, acordo formalizado, pagamento conciliado) — próxima fase.
