# Desenho — cobrança B2B (devedor pessoa jurídica)

Pedido do responsável (11/2026): o produto nasceu para cobrança de pessoa física. Este desenho cobre o que falta para
cobrar empresas com a mesma qualidade, sem enfraquecer as proteções do consumidor. Hoje o sistema já reconhece o CNPJ
(`PersonType.COMPANY`, coluna `tipo_pessoa` na carga), mas o cadastro, os contatos, o portal e as regras de
conformidade supõem pessoa física.

## 1. Princípios

1. **O regime vem da carteira, não do documento.** Uma carteira é de **consumo** (CDC: pessoa física, ou empresa
   consumidora final) ou **empresarial** (relação entre empresas; Código Civil e contrato). O tipo de pessoa diz quem é o
   devedor; o regime diz quais regras valem.
2. **Proteção nunca é retirada por inferência.** Na dúvida (carteira sem regime informado), vale o regime de consumo.
3. **Quem negocia pela empresa é uma pessoa identificada.** Acordo de PJ registra o representante que aceitou.
4. **LGPD continua valendo** para as pessoas naturais ligadas à empresa (sócios, representantes, pessoas de contato).

## 2. Lacunas e entregas

| # | Área | Hoje | Entrega |
| --- | --- | --- | --- |
| B1 | Cadastro da empresa | só nome e documento | **nome fantasia**; **matriz e filiais** pela raiz do CNPJ (8 primeiros dígitos, blind index próprio) com atalho "Outras unidades da empresa" na ficha |
| B2 | Pessoa de contato | contato sem pessoa | contato com **nome da pessoa** (criptografado) e **papel** (`FINANCE`, `ACCOUNTS_PAYABLE`, `OWNER_PARTNER`, `LEGAL`, `OTHER`) e indicação de quem decide |
| B3 | Representantes | só coobrigados (avalista, fiador, codevedor) | **representantes da empresa** (sócio administrador, procurador, outro) com **poder para negociar** e validade |
| B4 | Portal | desafio por data de nascimento não existe para PJ (a verificação falha sempre) | desafio próprio de PJ e **aceite por representante** identificado |
| B5 | Regime da carteira | não existe | `consumo` × `empresarial` na carteira, com os efeitos do §6 |
| B6 | Recuperação judicial e falência | não existe | **marcador de insolvência** do devedor PJ que suspende a cobrança (Lei 11.101/2005) e aponta o jurídico |
| B7 | Vulnerabilidade | marcadores de PF oferecidos para todos | superendividamento e categorias de PF só para pessoa física |
| B8 | Segmentação | sem corte PF/PJ | critério de **tipo de pessoa** em filas e segmentos; corte PF/PJ nos indicadores de contato ([inteligencia-de-contato.md](inteligencia-de-contato.md)) |
| B9 | Comunicação | modelos pensados para consumidor | variável `nome_fantasia` e saudação por tipo de pessoa nos modelos; janela de contato da política já é configurável (orientação no manual para horário comercial) |
| B10 | Score | um modelo para todos | **fase 2**: modelo por tipo de pessoa quando houver volume de PJ apurado (critério mínimo do treino) |

Dependem de provedor externo e viram issue (não código): consulta cadastral do CNPJ (situação na Receita, CNAE, quadro
de sócios para sugerir representantes — issue #31) e protesto/negativação de PJ (já na issue #13).

## 3. Cadastro da empresa (B1) — `debt`, `V0310__debt_company_profile.sql`

* `debt.debtor`: `trade_name` (criptografado, até 200), `document_root_hash` (blind index da raiz do CNPJ; nulo para
  CPF). Raiz calculada no domínio (`Document#cnpjRoot`).
* Ficha do devedor PJ: razão social, nome fantasia, CNPJ (mascarado como hoje), "Matriz" ou "Filial 0002" pelo sufixo,
  e atalho **"Outras unidades da empresa (3)"** para a lista de devedores filtrada pela raiz (o filtro recebe o id do
  devedor de referência, nunca o CNPJ na URL).
* Carga: colunas opcionais `nome_fantasia`. A coluna `data_nascimento` em linha de CNPJ é ignorada com aviso
  (`row.birth_date_company`).

## 4. Pessoa de contato (B2) — `debt`

* `debt.debtor_contact`: `person_name_encrypted` (até 120), `person_role` (`check`), `decision_maker boolean`.
  Editável no cadastro do contato (também para PF, onde o uso típico é "recado com a mãe" → papel `OTHER`).
* Carga: `telefone_N_contato`, `telefone_N_papel`, `email_N_contato`, `email_N_papel` (papel aceita `FINANCEIRO`,
  `CONTAS_A_PAGAR`, `SOCIO`, `JURIDICO`, `OUTRO`).
* Tela: o contato mostra "Ana (Financeiro) · decide" e o operador escolhe a quem ligar. O nome só aparece para quem vê o
  contato (mesmo mascaramento e log de acesso do valor).

## 5. Representantes (B3) e portal (B4)

### 5.1 Representantes — `debt.company_representative` (`V0310`)

**O representante não é devedor.** O sócio administrador ou o procurador não responde pela dívida (diferente do
coobrigado): não entra em `debt.debtor`, portanto nunca aparece na lista de devedores, na busca, em filas, segmentos,
score ou réguas. Entidade própria: `company_debtor_id`, `document_encrypted` + `document_hash` (blind index do CPF,
finalidade própria), `name_encrypted`, `role` (`MANAGING_PARTNER`, `ATTORNEY_IN_FACT`, `OTHER`, com `check`),
`can_negotiate`, `valid_until` (opcional), `evidence_note` (texto curto: "contrato social de 2024"), `ended_at`,
auditoria. RLS (`shared.enable_tenant_rls`); único por (empresa, CPF) enquanto vigente. Só devedor PJ tem
representantes; o representante é sempre CPF.

* Base legal: execução do contrato e legítimo interesse do credor em saber quem pode negociar pela empresa (dados
  mínimos: nome, CPF e papel). Retenção: a mesma do cadastro do devedor PJ ao qual está ligado; a anonimização da
  empresa apaga os representantes. Solicitação de titular do representante é atendida pelo CPF (fonte de
  compartilhamento própria em `shared/privacy`).
* Tela: seção **Representantes** na ficha do devedor PJ (inclusão, edição e encerramento com `DEBTOR_MANAGE`; CPF
  mascarado, revelação com `DEBTOR_SENSITIVE_VIEW` e log de acesso).
* Carga: `representante_1_documento`, `representante_1_nome`, `representante_1_papel` (`SOCIO_ADMINISTRADOR`,
  `PROCURADOR`, `OUTRO`), `representante_1_negocia` (`S`/`N`), e `representante_2_*`. Papel ou negocia em branco =
  não informado: a carga mantém o que está cadastrado (nunca apaga o papel ou o poder registrados à mão); na criação
  valem `OUTRO` e sem poder.
* Caso de uso para outros módulos: `CompanyRepresentativeUseCases#canNegotiate(companyId, cpfHash)` e
  `#representatives(companyId)` (visão sem documento em claro).

### 5.2 Portal para PJ

* **Desafio de conhecimento** (`KnowledgeChallenge`): devedor PJ nunca recebe o desafio de data de nascimento; quando o
  credor configurou `BIRTH_DATE`, o PJ responde `CONTRACT_SUFFIX` (4 últimos caracteres de um contrato ou título). A
  tela de configuração explica a regra. Corrige a falha de hoje (PJ nunca passava).
* **Identificação do representante**: depois do acesso, devedor PJ informa **CPF e nome de quem está negociando**. O
  portal confere com os representantes com poder para negociar e vigentes, **pelo blind index do CPF**, e exige que o
  primeiro e o último nome declarados batam com o cadastro (sem acento e sem diferenciar maiúsculas; nome que não
  confere conta como falha, com a mesma resposta) (o CPF digitado
  nunca vai a log técnico, evento, métrica ou auditoria; o evento de negócio registra só "confirmado"/"não confirmado").
  A resposta não revela quem está cadastrado: CPF que não confere segue no portal para **consulta** (dívidas, ofertas),
  mas o aceite vira **pedido de atendimento** (`PORTAL_NEGOTIATION`, "representante não confirmado"); a tela é a mesma
  nos dois casos até o aceite.
* **Limites**: tentativas de CPF (e nome) contadas **por devedor PJ e por IP** em janela de 30 min no mesmo mecanismo persistido
  do bloqueio progressivo (`portal.access_throttle`, chaves HMAC; categoria de retenção `PORTAL_ACCESS_THROTTLES`) —
  renovar a sessão não zera a contagem. Padrão: 5 falhas por devedor e 20 por IP bloqueiam a confirmação por 15 min,
  dobrando até 24 h. O desafio `CONTRACT_SUFFIX` de PJ usa os mesmos limites do desafio atual.
* **Aceite**: o termo e o registro do acordo levam o representante (id da pessoa, papel; CPF mascarado no PDF). O
  `AgreementAttributionRecorded` não muda.
* Sem representante cadastrado com poder → aceite sempre por atendimento humano (o portal avisa "um atendente vai
  concluir o acordo com você").

## 6. Regime da carteira (B5) — `organization`, `V0102__organization_portfolio_regime.sql`

`organization.portfolio.regime` (`CONSUMER` padrão, `BUSINESS`), editável na carteira (auditado), exposto em
`PortfolioView`. Efeitos:

| Regra | Consumo | Empresarial |
| --- | --- | --- |
| Alerta da revisão de carga para multa acima de 2% (CDC art. 52 §1º) | sim | não |
| Superendividamento (Lei 14.181/2021, instituto do CDC) | sim (PF) | não |
| Demais marcadores de vulnerabilidade | pelo tipo de pessoa (§8), independente do regime | pelo tipo de pessoa (§8), independente do regime |
| Modelos com tom de consumidor | sugeridos | a operação escolhe; manual orienta |
| Política de negociação padrão sugerida | multa 2%, juros 1% a.m. | encargos do contrato (sem teto do CDC; a política é configurada pela operação) |

O regime nunca muda contrato existente; a política continua sendo a fonte das condições.

**Implementação (11/2026).** `PortfolioRegime` no domínio (`cdcFineCapApplies`, `superIndebtednessApplies`); a criação
aceita o regime (ausente = consumo, também na API pública v1, que não expõe o campo) e a mudança é um caso de uso
próprio, `PortfolioUseCases#changeRegime` (`POST /portfolios/{id}/regime`, `PORTFOLIO_MANAGE`), com **motivo
obrigatório**, auditoria `portfolio.regime_changed` e evento de negócio de mesmo nome. Para as regras de outros
módulos, `PortfolioUseCases#consumerProtectionApplies(portfolioIds)` responde só um booleano, sem filtro de escopo
(carteira desconhecida ou lista vazia = consumo). Tela: coluna "Regime" e ação "Alterar regime" na lista de carteiras;
regime no cadastro. Efeitos entregues:

* **Revisão da carga**: em carteira empresarial o alerta `BALANCE_ABOVE_PRINCIPAL` por encargos acima do teto do
  consumidor não é gerado; **diverge do desenho** num ponto: o saldo a 10ⁿ vezes o principal continua alertado, porque é
  provável erro de separador decimal no arquivo, não regra do CDC ([importacao.md](importacao.md) §4.3).
* **Superendividamento**: §8.
* **Política padrão sugerida**: a política é por credor (não por carteira) e o "modelo de mercado" continua sendo o
  ponto de partida; a tela da política passou a dizer que a multa de 2% é do consumo e que a carteira empresarial usa
  os encargos do contrato. Modelos com tom de consumidor: orientação no manual da tela de carteiras (B9 trata os
  modelos).

## 7. Recuperação judicial e falência (B6) — `debt`, `V0311__debt_insolvency_proceeding.sql`

`debt.insolvency_proceeding`: `debtor_id` (só PJ), `kind` (`JUDICIAL_RECOVERY`, `EXTRAJUDICIAL_RECOVERY`,
`BANKRUPTCY`), `case_number` (CNJ, validado), `court`, `filed_on`, `status` (`ACTIVE`, `ENDED`), `ended_on`, nota,
auditoria. RLS (`shared.enable_tenant_rls`); `check` em `kind` e `status` e
`check ((status = 'ENDED') = (ended_on is not null))`; no máximo um `ACTIVE` por devedor. Registro e encerramento
manuais (`DEBTOR_MANAGE`) com motivo.

* Evento `DebtorInsolvencyChanged` (`shared.events.debt`: `debtorId`, `active`, `kind`).
* `collection`: motivo de inelegibilidade novo `DEBTOR_INSOLVENCY` (bloqueia contato e régua como os demais motivos);
  ao encerrar, a elegibilidade é reavaliada. Ficha do caso mostra "Empresa em recuperação judicial desde …" com atalho
  para o jurídico.
* Interação já registrada não é apagada; acordo em vigor não é cancelado automaticamente (decisão humana: a dívida pode
  estar no plano de recuperação). **Acordo novo é recusado** enquanto o processo estiver em curso (pagar crédito
  sujeito ao processo fora do plano fere a igualdade entre credores — Lei 11.101, arts. 49, 129 e 172): a negociação
  trata `DEBTOR_INSOLVENCY` como `case.not_eligible`, no portal e para o operador.
* **A empresa inteira**: o processo registrado numa unidade vale para matriz e filiais (mesma raiz do CNPJ). As
  filiais mostram o processo, ficam inelegíveis juntas e registrar, convolar ou encerrar exige escopo sobre os credores
  de todas as unidades. Cadastros antigos ainda sem a raiz (preenchida pela varredura diária) valem só para si.
* A tela só mostra as ações a quem pode executá-las (`canManage` da consulta: `DEBTOR_MANAGE` e escopo total); a nota e
  o motivo livres só vão a quem vê o cadastro do devedor (`DEBTOR_VIEW`/`DEBTOR_MANAGE`), e a trilha de auditoria
  guarda motivos fixos — o texto livre fica só no processo, que a anonimização neutraliza (com `insolvency.anonymized`).

**Implementação (11/2026)** — detalhes em [debt.md](debt.md) §7:

* Número CNJ validado por `shared.domain.CnjNumber` (movido do jurídico para não duplicar a regra); o ano do número é o
  da data do pedido.
* A **origem da informação** (nota) é obrigatória no registro e é o motivo da auditoria; encerramento e convolação
  pedem data e motivo.
* **Convolação** em falência (art. 73) muda o tipo do mesmo processo (`converted_from`/`converted_on`), sem a janela
  sem suspensão que "encerrar e registrar de novo" abriria.
* Registrar, convolar e encerrar exigem escopo sobre **todos** os credores do devedor (a suspensão vale em todos); a
  consulta aceita `DEBTOR_VIEW` ou `CASE_VIEW` (quem atende o caso vê o aviso).
* `DEBTOR_INSOLVENCY` não precisou de migration no collection (`eligibility_reasons` é texto sem `check`;
  `V0611` não foi usada). Na régua, o caso inelegível sai do segmento ou tem a etapa pulada; não há pausa própria
  ([campanhas.md](campanhas.md) §3.4).
* Aviso `app-insolvency-notice` na ficha do devedor (com as ações) e na ficha do caso; o atalho abre o caso jurídico
  aberto da empresa (um → o caso; vários → menu; nenhum → orientação de habilitar o crédito).
* A anonimização do devedor neutraliza a nota e o motivo (texto livre); o processo, dado público, permanece.

## 8. Vulnerabilidade (B7) — `compliance`

`VulnerabilityCategory` ganha a aplicabilidade por tipo de pessoa: superendividamento e as categorias de pessoa natural
só para CPF (`422 vulnerability.not_applicable_to_company`); a tela esconde a ação para PJ. `OTHER` continua para todos.
O regime da carteira não retira marcador de vulnerabilidade de pessoa física: só o superendividamento deixa de ser
oferecido em carteira empresarial (§6).

**Implementação (11/2026)** — [conformidade.md](conformidade.md) §1.5: `VulnerabilityApplicability` (domínio puro).
Superendividamento é recusado (`422 vulnerability.not_applicable_to_business_portfolio`) quando **todas** as carteiras
em que o devedor tem títulos são empresariais; basta uma de consumo, em qualquer credor (consulta sem escopo), para
continuar valendo. O selo devolve `availableCategories` e o diálogo oferece só essas, explicando o motivo. Vale também
na confirmação da sugestão do assistente; marcadores já registrados não são retirados.

## 9. Segmentação (B8)

* `collection`: critério `person_type` (`INDIVIDUAL`/`COMPANY`/nulo) na fila; o caso guarda `debtor_person_type`
  (preenchido na abertura pelo `DebtorView`), na mesma migration do critério de contato novo (`V0610`), entregue junto.
* `campaign`: critério `personType` no segmento (SQL e memória), coluna em `audience_case`.
* **Implementado** junto com o contato novo: `collection_case.debtor_person_type` e `queue.person_type` (`V0610`, casos
  existentes preenchidos pela migration a partir do cadastro do devedor; os novos, na abertura, por
  `DebtorSegmentationQueries`), `audience_case.debtor_person_type` (`V1103`, lido do caso). Tipo exigido só seleciona
  casos com o tipo conhecido. Telas: critério "Tipo de pessoa" na fila e no segmento.

## 10. Testes

Domínio (raiz do CNPJ, representantes com validade, regime), `CompanyDebtorIT` (carga com nome fantasia, contatos com
pessoa e representantes; filiais pela raiz), `PortalCompanyIT` (desafio PJ, representante que confere e que não confere
— mesma resposta, aceite com representante, aceite sem representante vira atendimento), `InsolvencyIT` (marcador →
inelegível → encerrado → elegível), testes do frontend.

## 10a. Entrega de B1–B4 e B9 (11/2026) — o que divergiu do desenho e por quê

Implementação: [debt.md](debt.md) §7 (empresa, pessoa de contato, representantes), [portal.md](portal.md) §2a
(portal para PJ), [layout de importação](../../negocio/layout-importacao.md) §2.0 (colunas) e
[templates](../../negocio/templates-cobranca.md) (variáveis). Divergências:

* **Caso de uso do representante para outros módulos**: em vez de `canNegotiate(companyId, cpfHash)`, o debt expõe
  `negotiator(companyId, cpf)` (e `hasNegotiators`, `get`). O blind index do CPF tem finalidade própria do debt; se o
  portal calculasse o hash, ele precisaria conhecer a finalidade e a chave de outro módulo (acoplamento). O CPF só
  trafega em memória entre os módulos e nunca é registrado.
* **Pessoa de contato mascarada na lista**: "o nome só aparece para quem vê o contato (mesmo mascaramento do valor)" foi
  implementado como o valor: a lista mostra "A*** (Financeiro) · decide"; o nome completo aparece na revelação auditada
  do contato. Na edição, nome vazio mantém o atual (a tela não conhece o nome completo) e "Sem pessoa" remove.
* **Confirmação bloqueada**: com o limite estourado, a declaração continua aceita com a mesma resposta, sem conferir o CPF
  (o aceite vira atendimento), em vez de recusar com 429 — recusar diria ao atacante que as tentativas anteriores
  estavam erradas.
* **Canal do atendimento no aceite**: o aceite da empresa exige `preferredChannel` (por onde o atendente fala se precisar
  concluir), pedido sempre — confirmada ou não — para a tela ser a mesma nos dois casos.
* **Motivo do pedido**: o "PORTAL_NEGOTIATION, representante não confirmado" virou o motivo próprio
  `COMPANY_ACCEPTANCE` no portal (V1305), que vai ao communication como `PORTAL_NEGOTIATION`; o devedor não o escolhe.
* **Raiz do CNPJ de cadastros anteriores**: preenchida por varredura diária (`debt.document_root_backfill`) e pela
  próxima carga, porque o blind index depende da chave da aplicação (a migration não consegue calculá-lo).
* **Solicitação de titular do representante**: entregue a fonte `shared.privacy.RelatedPersonSource` (debt); o uso no
  compliance (localizar o titular que não é devedor) ficou como pendência ([conformidade.md](conformidade.md) §8.1).
* **Coluna `data_nascimento` em CNPJ**: ignorada com aviso na carga e recusada no cadastro manual
  (`debtor.birth_date_company`); cadastros antigos de empresa com data são limpos na próxima edição.
* **Nome declarado no portal** (correção de revisão): além do CPF, o primeiro e o último nome declarados precisam bater
  com o cadastro (`portal.domain.service.DeclaredName`, sem acento e sem diferenciar maiúsculas; nomes do meio livres).
  Antes bastava o CPF. Nome que não confere conta como falha no limite, com a mesma resposta (anti-enumeração).
* **Trava do limite antes de decidir** (correção de revisão): `RepresentativeConfirmation#declare` trava as duas linhas
  de `portal.access_throttle` (empresa e depois IP, sempre nessa ordem; `update` com transição identidade, que faz
  `select … for update`) antes de ler o bloqueio; a trava dura até o commit de `declareRepresentative`
  (`@Transactional`). Antes a leitura sem trava deixava declarações simultâneas furarem o limite.
* **Carga não apaga papel nem poder** (correção de revisão): `representante_N_papel` e `representante_N_negocia` em
  branco passaram a significar "não informado" (`RepresentativeRecord.role`/`canNegotiate` nulos) e mantêm o valor
  atual; antes viravam `OTHER` e `N` e sobrescreviam o cadastro manual. Na criação os padrões continuam `OTHER` e sem
  poder.

**Pendências** (registradas na revisão de código):

* O aceite de PJ pelo **operador** (tela da negociação) e pelo **assistente** ainda não registra o representante que
  aceitou em nome da empresa — só o portal leva o representante ao termo e ao registro do acordo.
* A **retenção do representante encerrado** (hoje fica até a anonimização da empresa) e a **cifra de `evidence_note` e
  `end_reason`** (hoje em claro, texto livre curto que pode conter dado pessoal) dependem de decisão do responsável.
* `RepresentativePrivacySource` precisa alinhar o escopo por credor com `DebtorVisibility.creditorsOf` antes de ser
  ligada ao compliance (solicitação de titular do representante).

## 11. Decisões (numeradas em `decisoes-autonomas.md` na entrega)

* Regime pela carteira, padrão consumo.
* Representante obrigatório para aceite de PJ no portal; sem confirmação, atendimento humano.
* Representante em entidade própria, fora do cadastro de devedores.
* Vulnerabilidade pelo tipo de pessoa, independente do regime; só o superendividamento depende do regime.
* Recuperação judicial suspende a cobrança (inelegível) sem cancelar acordo em vigor.
* Raiz do CNPJ para agrupar matriz e filiais (grupo econômico além da raiz fica para a consulta cadastral externa).
* Score por tipo de pessoa adiado para a fase 2 (volume mínimo de dados).
