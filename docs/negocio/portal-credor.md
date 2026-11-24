# Portal do credor

> **Situação:** proposta de 24/11/2026, **aprovada pelo responsável em 24/11/2026** com todas as recomendações da §10.
> MVP (§8) em implementação. Desenho técnico em [`tecnico/desenho/portal-credor.md`](../tecnico/desenho/portal-credor.md).
> Montada a partir da análise de negócio (`analista-negocio`) e do desenho de experiência (`revisor-ux`).

## 1. Objetivo

Dar ao credor que cede carteiras à assessoria **transparência operacional** e **agilidade**: acompanhar a operação e
resolver no portal o que hoje anda por e-mail e planilha (prestação de contas, contestações, pagamentos feitos direto ao
credor, suspensões e baixas). Tudo com escopo por credor, auditoria de cada ação e o mínimo de dado pessoal.

Critério de sucesso (não é "mais acordos"):

- tempo entre o envio do demonstrativo e o aceite;
- tempo de retorno do credor nas contestações ("Retorno do credor", [indicadores.md](indicadores.md) §7);
- tempo entre o pagamento direto ao credor e a baixa do título (cobrança indevida evitada);
- **acordos em dia** (o acordo que o devedor cumpre) como número principal da tela inicial.

**Ponto de partida:** quase tudo já existe por trás. A [API pública](api-publica.md) já faz, por credor, consulta de
títulos, casos, acordos e demonstrativos, informe de pagamento direto, suspensão, cancelamento e devolução de títulos,
abertura e resposta de contestação. A tela "Posição do credor" já calcula o repasse em aberto. **O que falta** é a
identidade do credor (hoje não há usuário do credor — decisões 109 e 788, [prestação de contas](../tecnico/desenho/prestacao-de-contas.md)
item 15) e uma fila "Pedidos dos credores" na assessoria.

## 2. Quem usa e o que quer saber

| Persona (lado do credor) | Perguntas | Ações típicas |
| --- | --- | --- |
| Gestor de recuperação | Quanto está em cobrança? Quanto foi recuperado? Os acordos estão sendo cumpridos? | Acompanhar, pedir suspensão ou devolução |
| Financeiro | Quanto recebo neste período? O demonstrativo bate com meu extrato? O repasse saiu? | Aceitar ou contestar o demonstrativo, informar pagamento direto |
| Jurídico | Que contestações esperam minha posição, e até quando? | Responder contestação com documentos, pedir evidências |
| Compliance / DPO | Há reclamações contra minha marca? Quem do meu time acessou o quê? | Ver conformidade agregada e a atividade do próprio time |

## 3. Telas (arquitetura de informação)

Área separada em `/credor/...`, com moldura, sessão e token próprios (como o portal do devedor), marca da assessoria e
o nome do credor no contexto. Cada item do menu só aparece com a permissão do perfil do credor.

| Menu | Conteúdo |
| --- | --- |
| **Início** | 5 indicadores, "Aguardando você" (pendências com prazo), recuperado por carteira (5 primeiras + "Outros"), meus pedidos recentes |
| **Carteiras** | Lista e ficha da carteira: saldo, casos ativos, recuperado, taxa de recuperação, acordos em dia, casos por situação (cada linha leva à lista filtrada) |
| **Casos** | Busca por contrato, título ou documento (termo fora da URL); ficha com resumo, acordo, títulos, linha do tempo em linguagem do credor e pedidos do caso |
| **Pedidos** | Central única com abas "Aguardando você", "Enviados por você" e "Concluídos"; cada pedido tem protocolo, situação, os dois prazos e mensagens |
| **Prestação de contas** | Demonstrativos enviados, período em aberto (posição estimada), aceite e contestação, repasses e comprovantes |
| Menu do usuário | Minha atividade, Segurança da conta (MFA, sessões), Notificações, Ajuda, Sair |

**Indicadores da tela inicial** (cada um com o "i", data de corte e variação com sinal e texto):

| Indicador | Definição curta |
| --- | --- |
| Recuperado no período | Pagamentos confirmados, já descontados os estornos; inclui os pagamentos diretos ao credor que a assessoria confirmou |
| Acordos em dia | Acordos ativos sem parcela vencida e não paga ÷ acordos ativos |
| Saldo em cobrança | Saldo atual dos títulos em cobrança na assessoria, hoje |
| Repasse estimado | Líquido do período em aberto, calculado agora e sujeito ao fechamento; o texto diz quem paga quem |
| Aguardando você | Pedidos e contestações que dependem da resposta do credor; em destaque os que vencem em até 2 dias |

```
┌ [Assessoria Aurora]   Credor: Banco Exemplo                     🔔3  Ana ▾ ┐
│ Início  Carteiras  Casos  Pedidos (3)  Prestação de contas                  │
│ [Período: Este mês ▾] [Carteiras: Todas (12) ▾]      Atualizado às 14:32    │
│ ┌Recuperado ⓘ┐ ┌Acordos em dia ⓘ┐ ┌Saldo em cobrança ⓘ┐ ┌Repasse estimado ⓘ┐ │
│ ┌ Aguardando você (3) ─────────────────────────────────────── Ver todos ┐    │
│ │ ⚠ Contestação de devedor · Caso CT-88231 · responder até 26/11        │    │
│ │ ⚠ Prestação de contas out/2026 · aceitar ou contestar até 30/11       │    │
│ ┌ Recuperado por carteira (5 + Outros) ┐ ┌ Meus pedidos recentes ┐          │
```

Estados de carregando, vazio e erro com próximo passo ("Nada depende de você agora."; erro com "Tentar novamente" e
"Abrir pedido"); celular sem rolagem lateral, com ações num rodapé fixo; WCAG 2.2 AA.

## 4. O que o portal mostra e o que nunca mostra

**Mostra** (agregado e posição do próprio credor por padrão; detalhe só quando a ação exige):
carteira e saldo, recuperação, qualidade dos acordos (1ª parcela, quebra), repasse em aberto, demonstrativos enviados com
arquivos e hash, repasses e comprovantes, contestações aguardando o credor, os próprios pedidos e pagamentos informados,
reclamações e conformidade agregadas, importações.

**Linha do tempo do caso em linguagem do credor**, sem enums nem nomes internos: "Caso recebido da carteira X",
"Devedor atendido pela assessoria" (no máximo uma linha por dia, sem canal nem conteúdo), "Acordo fechado em 6
parcelas", "Parcela 2 de 6 paga", "O devedor contestou a cobrança", "Título devolvido a você".

**Nunca mostra:**

| Dado | Por quê |
| --- | --- |
| Conversas, texto das interações, gravações e transcrições | Acompanhar a operação não exige o conteúdo; alto risco (decisão 100) |
| Contatos do devedor; documento sem máscara | Minimização ([api-publica.md](api-publica.md) §3); o credor já tem os dele |
| Categoria de vulnerabilidade (saúde é dado sensível) | LGPD art. 11 — no máximo "cobrança suspensa por conformidade", sem motivo |
| Score, observações internas, relato do devedor, texto de reclamações | Finalidade interna |
| Nome do negociador, produtividade, remuneração | Dado de pessoas da assessoria |
| Margem, custos, tarifas, política e alçadas da assessoria | Informação comercial interna |
| Qualquer dado ou contagem de outro credor | Escopo por credor e RLS |

## 5. Ações do credor

Regra central: **o que é declaração do credor ou reduz a pressão sobre o devedor vale na hora; o que mexe em valor,
saldo, comissão ou aumenta a pressão vira pedido que a assessoria aprova.** Toda ação gera `AuditEntry`, evento de
negócio sem dado pessoal e aparece em "Minha atividade".

| Ação | Efeito | Regras principais | Já existe por trás |
| --- | --- | --- | --- |
| Aceitar demonstrativo | **Na hora** | Nova verificação do MFA; termo com SHA-256 dos arquivos, usuário, IP e instante; correções só no período seguinte | `settlement` (hoje registrado pela assessoria) |
| Contestar demonstrativo | **Na hora** no estado; correção por **pedido** | Motivo, itens e anexos; nunca altera valores — a assessoria corrige por ajuste (decisões 108 e 109) | `settlement` |
| Informar pagamento direto | **Pedido** | Títulos ou parcelas, valor (`appMoney`), data não futura, comprovante obrigatório, `Idempotency-Key`; enquanto em análise a cobrança desses títulos pausa e a quebra do acordo fica suspensa; confirmado → baixa, parcela aplicada e recebimento "pago ao credor" (o credor deve a comissão) | `payment` §5 (`CREDITOR_DIRECT`) |
| Suspender cobrança | **Na hora** (protege o devedor); retomar é **pedido** | Motivo; com acordo em vigor, avisa que as parcelas continuam devidas | `debt` (suspender/reativar) |
| Baixa ou devolução de título/caso | **Pedido** | Motivo; impacto mostrado (acordo em dia, pagamento em trânsito, contestação); confirmação digitando o contrato | `debt` (cancelar, devolver) |
| Responder contestação do devedor | **Na hora** o registro; **nunca resolve** a contestação | Posição + resumo + até 5 documentos pela quarentena; prazos ao devedor não mudam (decisão 784) | `collection` (resposta do credor, decisão 798) |
| Abrir contestação (credor reconhece erro) | **Na hora** | Tipo e títulos | `collection` |
| Enviar documento | **Pedido** de juntada | Quarentena; vira evidência só após verificação | `collection` (decisão 815) |
| Pedir evidências | **Pedido** | Finalidade obrigatória (fica registrada); entrega no portal com as regras da decisão 788; gravação nunca pelo portal | `collection` (link seguro) |
| Avisar mudança no cadastro da carteira | **Pedido** | Nunca muda contrato nem comissão; dados bancários com 2ª pessoa | `organization` |
| Aprovar acordo fora da política | **Fase 3**, se aprovado | Conflita com "fora da política ninguém concede" (§9) | conceito novo |

**Mensagens por pedido, sem chat livre**: cada conversa tem contexto, prazo e responsável. Prazos visíveis nos dois
sentidos ("A assessoria responde até…", "Você responde até…"). Notificação no portal e por e-mail **sem** nome,
documento nem valores (só protocolo, tipo, prazo e link). Na assessoria, nova fila "Pedidos dos credores" em
Pendências.

## 6. Compliance

- **Papéis LGPD:** credor = controlador dos dados do devedor, assessoria = operadora (coerente com a decisão 1106); dos
  dados de acesso dos usuários do credor, a assessoria é controladora. Aviso de privacidade próprio, versionado.
  *Premissa para o jurídico confirmar o contrato de operador com o credor.*
- **CDC:** o portal não dá alavanca para aumentar a pressão (reativação e régua só por pedido); pagamento direto
  confirmado rápido evita cobrança indevida (art. 42); superendividamento aparece como situação do caso.
- **Auditoria e logs:** `creditor_user.*`, `creditor_request.*`, aceite e contestação com origem "portal do credor",
  pagamento informado por externo; log de acesso em toda abertura de caso com nome do devedor, download e exportação;
  eventos de negócio e métricas documentados no catálogo na mesma mudança.
- **Retenção:** categorias novas (sessões e acessos, pedidos, documentos enviados), prazos com o jurídico.

## 7. Identidade e acesso

- **Ator próprio `CREDITOR`**, separado do usuário interno: token com audiência própria e API própria, nunca aceito na
  API interna nem no portal do devedor; perfis do credor (gestor, financeiro, jurídico, compliance, leitura) com
  permissões **disjuntas** das internas.
- Convite com link de uso único, senha e **MFA obrigatório antes de ver qualquer dado**; nova verificação do MFA no
  aceite, no pagamento direto e na baixa.
- Revogação imediata, desativação por inatividade e recertificação periódica pela assessoria.
- Anti-enumeração (login, "esqueci a senha" e busca respondem igual) e limite de uso por IP e por conta.

## 8. Fatiamento

| Fase | Conteúdo |
| --- | --- |
| **MVP** | Identidade (convite pela assessoria, MFA, revogação, limites); Início; prestação de contas (aceite, contestação, arquivos); contestações aguardando o credor (responder com documentos); informe de pagamento direto (sempre revisado); Minha atividade; auditoria, logs e eventos |
| Fase 2 | Carteiras e Casos completos; suspensão, baixa, devolução e retomada; pedir evidências; juntada de documento; repasses e comprovantes; conformidade agregada; notificações por e-mail; administrador do credor pede convites |
| Fase 3 | Exceção de acordo (se aprovada); mudança cadastral; autorização de ação judicial e custas; recorte por carteira; 2ª pessoa do credor; assinatura eletrônica (se aprovada) |

Alternativa menor (**MVP-a**): identidade + prestação de contas + posição do repasse — é o item I11 (onda 5.6) da
[revisão de produto](revisao-produto-2026-11/README.md).

**Critérios de aceite do MVP (resumo):**

1. Credor sem portal habilitado: convite não aparece e o servidor recusa.
2. Ativação exige senha e MFA antes de qualquer dado; convite de uso único e com validade.
3. Login e "esqueci a senha" respondem igual para e-mail existente ou não; excesso de tentativas → 429 e registro.
4. Token do portal do credor recusado na API interna e no portal do devedor (e vice-versa); revogação derruba sessões.
5. Id de outro credor → 404 e `SCOPE_DENIED`; nenhuma resposta traz contato, documento sem máscara, conversa, gravação,
   vulnerabilidade, score, negociador, custo ou margem (teste de contrato).
6. Aceite com nova verificação do MFA → `ACKNOWLEDGED` com autor externo, IP, instante e hash; contestação exige motivo
   e não altera valores.
7. Resposta a contestação passa pela quarentena, encerra o "aguardando o credor" e **não** resolve a contestação.
8. Pagamento direto: sem comprovante, destino ou com data futura é recusado; mesma `Idempotency-Key` devolve o mesmo
   informe; em análise suspende a quebra; confirmado baixa o saldo e gera o recebimento "pago ao credor"; recusado
   mostra o motivo ao credor.
9. Toda ação gera `AuditEntry` e evento de negócio sem dado pessoal.

## 9. Conflitos com regras e decisões atuais

| Regra ou decisão | Conflito | Proposta |
| --- | --- | --- |
| Decisão 109 e prestação de contas item 15 | Aceite e contestação só registrados pela assessoria | Dois caminhos (assessoria registra **ou** o credor declara), com origem e autor; a máquina de estados não muda |
| Decisão 788 | Rejeitou "portal com login" por estar fora do pedido | Manter o link seguro para credor sem portal e reaproveitá-lo dentro do portal |
| Negociação e alçadas §9.4: "fora da política ninguém concede" | Choca com "credor aprova acordo fora da política" | Fase 3, por exceção autorizada por proposta — ou não ter (decisão 4) |
| API pública: cancelar e devolver são diretos | No portal viram pedido | Aceitar a diferença por ora (decisão 5) |
| Pagamentos §5: até R$ 500 confirma sozinho | No portal, proposta de revisão sempre | Regra por canal (decisão 3) |
| Decisões 816 e 819: antivírus desligado por padrão | Mais arquivos externos chegando | Issue B (§11) |

## 10. Decisões do responsável (aprovadas em 24/11/2026: vale a coluna "Recomendação")

| # | Decisão | Opções | Recomendação |
| --- | --- | --- | --- |
| 1 | Modelo de identidade | ator próprio `CREDITOR` / usuário interno com perfil "credor" | **Ator próprio** — um erro de configuração nunca dá acesso interno a alguém de fora (é o maior esforço do MVP) |
| 2 | Escopo da conta | um credor por conta / seletor de vários credores / recorte por carteira | **Um credor por conta** no MVP; seletor e carteira depois |
| 3 | Pagamento direto pelo portal | sempre revisado / automático até R$ 500 como na API | **Sempre revisado**, com o motivo da recusa visível ao credor |
| 4 | Acordo fora da política | exceção por proposta (fase 3) / credor aprova nova versão da política / não ter | Exceção por proposta na fase 3, com 2ª pessoa do credor acima de um limite |
| 5 | Diferença com a API pública | aceitar / tornar a API "pedido" também | **Aceitar** por ora (mudar a API quebra integrações e exige changelog) |
| 6 | Gravações e conversas | nunca no portal / ouvir as dos próprios casos | **Nunca**; gravação só por pedido de evidência, após posição do jurídico (decisão 100) |
| 7 | Vulnerabilidade | nada / "cobrança suspensa por conformidade" / selo "atenção especial" | **"Cobrança suspensa por conformidade"**, sem motivo |
| 8 | Suspensão pelo credor | na hora / pedido | **Na hora** (protege o devedor); retomada por pedido |
| 9 | Dado do devedor nas listas | só a referência do credor + documento mascarado / primeiro nome + inicial | Referência do credor + documento mascarado; nome só na ficha, com log de acesso |
| 10 | Quem convida os usuários do credor | só a assessoria / administrador do credor | **Só a assessoria** no MVP; o credor pede convites na fase 2 |
| 11 | Tamanho do MVP | MVP completo (§8) / MVP-a (I11) | **MVP completo** — inclui o pagamento direto, que reduz cobrança indevida |
| 12 | Marca | assessoria / credor / as duas | **Assessoria**, com o nome do credor no contexto |
| 13 | Prazos de inatividade, recertificação e retenção | premissas: 90 dias, semestral | Validar com jurídico e compliance |

## 11. Itens que dependem de aprovação

| Issue | Para quê | Alternativa sem custo |
| --- | --- | --- |
| A. E-mail transacional (convites, senha, avisos) — usa o SMTP existente; registrado na #9 (SES) | Entregar convites e avisos | Convite gerado na tela e entregue pela assessoria; sem avisos automáticos |
| B. Antivírus para documentos de credores — **issue #33** | Comprovantes, contratos e respostas vindos de fora | Quarentena só com tipo e limites (risco aceito) |
| C. (não aberta; mesma origem no MVP) Subdomínio próprio (`credor.<domínio>`) — ver #3 e #4 | Separar cookies e marca | Mesma origem, rota `/credor` |
| D. (não aberta; limite na aplicação no MVP) WAF com limite de taxa no login — ver #5 e #18 | Proteção de borda | Limite por instância + bloqueio por conta |
| E. (não aberta; aceite eletrônico autenticado no MVP) Assinatura eletrônica no aceite | Se o jurídico exigir mais que login + MFA + hash | Aceite eletrônico autenticado (MP 2.200-2, art. 10 §2º) |

## 12. Riscos

| Risco | Mitigação |
| --- | --- |
| Usuário do credor ver dado de outro credor ou dado interno | Ator separado, escopo no servidor, RLS, teste de contrato das respostas |
| Conta do credor comprometida desviando repasse ou cancelando títulos | MFA, nova verificação, baixa e dados bancários como pedido com 2ª pessoa, IP permitido opcional |
| Credor não informar pagamento direto (para não pagar comissão) e o devedor seguir sendo cobrado | Informe simples; contestação "já paguei ao credor" vira pendência com prazo; indicador de pagamentos diretos por credor |
| Números diferentes do sistema do credor | Definição e data de corte em cada número; "pago direto confirmado" separado |
| Pedidos demais para a assessoria | Tipos fechados, aviso de pedido duplicado, prazos e fila própria |
| Esforço de identidade subestimado | Avaliação do `arquiteto` antes de comprometer o MVP; MVP-a como plano B |
