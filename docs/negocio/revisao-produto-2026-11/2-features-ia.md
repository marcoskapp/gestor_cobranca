# Estudo 2 — Amplitude de features, gaps para produção e uso de IA

Data: 06/11/2026 · Base: `master` em `9b4c60dc` · Autor: análise de PO/domínio (cobrança extrajudicial e judicial no Brasil)

**Legenda.** **[F]** = fato verificado no repositório (doc + código ou issue); **[S]** = suposição / juízo de domínio, a
validar. Esforço: **P** (até ~1 semana), **M** (2–4 semanas), **G** (> 1 mês ou depende de terceiro). "**Precisa de
aprovação (issue)**" = depende de provedor externo, custo novo ou serviço AWS fora da lista; pela regra do projeto vira
issue, não código.

---

## 1. Inventário — o que a solução já cobre

Tamanho do código (Java, `backend/src/main/java/br/com/gestorcobranca`): ~245 mil linhas em 23 módulos; 238 testes
unitários e 94 ITs com PostgreSQL real [F]. Frontend com telas em todos os módulos (`frontend/src/app/features/*`) e
portal do devedor separado (`frontend/src/app/portal`) [F]. Várias listas de "Pendências" dos desenhos estão
**desatualizadas** (ex.: `pagamentos.md` §14 e `campanhas.md` §11 ainda dizem que falta frontend, mas as telas existem)
[F].

| Módulo | Capacidade | Maturidade | Evidência / observação |
| --- | --- | --- | --- |
| `organization` | Assessoria (tenant), unidades, credores, contratos versionados, carteiras | **Completo** | `visao-geral.md` §2 |
| `identity` | Usuários, perfis configuráveis, permissões, equipes, escopo por credor, MFA, sessões, log de acesso | **Completo** | `perfis-de-acesso.md`, `seguranca.md` |
| `debt` | Devedor, contatos (cifrados + blind index), títulos, parcelas, garantias, coobrigados, prescrição, correção de valor com dupla aprovação | **Completo** | `desenho/debt.md`, `correcao-de-valor.md` |
| `ingestion` | Carga CSV/XLSX/API idempotente, conferência, erros por linha; **Importação PDF com IA** (mapa proposto pelo Claude + leitura visual) | **Completo** (OCR externo pendente, issue #23) | `importacao.md`, `importacao-pdf.md` |
| `compliance` | Porteiro de contato (janelas, feriados, limites diário/semanal, intervalo), opt-out, restrições, bases legais, aviso de privacidade, pedidos de titular (LGPD), retenção e descarte | **Completo** (sem marcador de vulnerabilidade) | `compliance/domain/model/*` (ContactWindow, ChannelRule, DataSubjectRequest, RetentionPolicy) |
| `collection` | Caso, elegibilidade, filas, distribuição, "próximo caso", tabulação com ação automática, retornos, promessas, **contestações** com canal formal ao credor e leitura de resposta por e-mail (SES inbound) | **Completo** | `desenho/collection.md` |
| `negotiation` | Políticas versionadas, motor de cálculo, escada de concessões, alçadas (humano e IA), propostas, acordos, parcelas, quebra (com suspensão por pagamento em revisão), renegociação, termo | **Completo** | `negociacao-e-alcadas.md` |
| `payment` | **Pix** (API padrão Bacen, webhook, baixa automática), pagamentos externos com revisão segregada, alocação, conciliação por **CSV genérico**, estornos | **Parcial** — boleto e link só como portas (sem adaptador); CNAB 240/400 sem leitor; devolução Pix manual | `pagamentos.md` §14; `BoletoProvider`/`PaymentLinkProvider` sem implementação |
| `settlement` | Comissão da assessoria versionada, razão, demonstrativo ao credor com aprovação segregada, exportação conciliável (CSV/XLSX + SHA-256), repasses | **Completo** (sem acesso do credor para aceite/contestação; sem envio automático) | `prestacao-de-contas.md` §13 |
| `communication` | WhatsApp Cloud API, e-mail SMTP, templates com aprovação registrada, conversas com dono (assistente/fila/operador), inbox em tempo real, SLA de 1ª resposta | **Parcial** — **SMS sem adaptador** (`ChannelAccountService` recusa SMS fora do sandbox); sem mídia (recebida sem arquivo, envio só texto); sem sincronização de templates com a Meta; e-mail sem bounce (SES #9); envio dentro da transação | `comunicacao.md` §9 |
| `campaign` | Segmentos (com score), réguas multicanal, janelas de envio, A/B, atribuição | **Parcial** — ação `OFFER` sem adaptador do `CampaignOfferPort`; SMS depende do #12 | `campanhas.md` §11 |
| `telephony` | Clique para ligar, discador progressivo/preditivo com controle de abandono, porteiro por tentativa, ACD/presença, gravações, multiprovedor com contingência, softphone WebRTC (Twilio) | **Código completo, não homologado** — adaptadores Twilio/Plivo prontos; Alvaria testada com servidor falso; Tarifando desligada; falta conta/número BR; sem escuta/sussurro | `telefonia.md` §12; issue #12 (comentários) |
| `portal` | Autonegociação do devedor (link único ou CPF + código), simulação, proposta, formalização, Pix | **Completo para Pix** — boleto/link não; contestação pelo portal não vira contestação no collection | `portal.md` §9 |
| `scoring` | Score de propensão (scorecard + regressão logística L2), campeão/desafiante, PSI/AUC, recuperado × esperado, explicação | **Completo** (não é IA generativa; lista fechada de 17 features) | `scoring.md`, `score-propensao.md` |
| `assistant` | IA de negociação (Bedrock/Claude): modos sombra/copiloto/autônomo, identidade, gatilhos obrigatórios, guardrails determinísticos, alçada própria, Pix na conversa, revisão amostral, painel de qualidade | **Código completo, não homologado** — bloqueado pela decisão 371 (transferência internacional); só WhatsApp; conversas de referência pendentes | `assistente.md` §6.6, §11 |
| `analytics` | Painéis e relatórios (operacional, recuperação, conversão, produtividade, contestações), exportação, denominador explícito | **Completo** (sem custo de canal no custo por real recuperado) | `analytics.md` §10 |
| `incentive` | Métricas do negociador, atribuição humano × IA, planos de comissão, apuração, extrato, contestação, **monitoria manual** (formulários QA, ocorrências) | **Completo** (alguns indicadores sem fonte: propostas, handoffs) | `remuneracao-variavel.md` §10.5 |
| `legal` | Workflow jurídico: elegibilidade, viabilidade, autorização do credor, medidas extrajudiciais (notificação, **protesto, negativação**, execução de garantia), processos, prazos em dias úteis, audiências, custas, escritórios | **Parcial** — todas as integrações (DataJud, protesto, birôs, registros, gravames, publicações) só com adaptador **manual** | `juridico.md`; issue #13 |
| `pendingwork` | "Minhas pendências" agregadas | **Completo** | `pendencias.md` |
| `helpdesk` | Chat de suporte com IA: manual como base, consultas só leitura como o usuário, chamado no GitHub mascarado | **Código completo, não homologado** (mesma decisão 371; repositório de chamados público) | `chat-suporte.md` §13 |
| `publicapi` | API pública v1 para credores: tokens com escopo, OpenAPI versionado, medição de consumo e faturamento | **Parcial** — sem webhooks de saída (#20), sem limite global/WAF (#17, #18) | `api-publica.md` §12 |
| Infra (Terraform) | VPC, ALB, ASG EC2, RDS, S3, CloudWatch, guardas de aprovação | **Parcial** — ACM/Route 53/WAF/SNS/SSM/KMS/GuardDuty/CI-CD aguardando aprovação (#3–#11) | `ativacao-aws.md` §1.7, §12 |

**Leitura de PO [S].** A amplitude funcional é **acima** da média dos CRMs de cobrança nacionais em governança
(segregação, alçadas, auditoria, prestação de contas, remuneração variável, jurídico, score com governança de modelo).
O que separa o produto do dia 1 de produção **não é falta de módulo**, e sim **integrações com o mundo real** (SMS,
boleto/CNAB, telefonia homologada, birôs) e **pré-requisitos de go-live** (aprovações, jurídico, operação).

---

## 2. Comparação com um CRM de cobrança de mercado

| Capacidade de mercado | Situação | Nota |
| --- | --- | --- |
| Discador preditivo / integração com telefonia | Código pronto, **sem homologação** | Twilio/Plivo prontos; falta conta + número BR + validação jurídica/Anatel [F] |
| Régua multicanal | WhatsApp + e-mail **sim**; **SMS não** | [F] |
| Boleto registrado / Pix com baixa automática | Pix **sim**; boleto **não** | [F] |
| Arquivo de retorno bancário (CNAB 240/400) | **Não** (CSV genérico) | [F] decisão 79 |
| Negativação (Serasa/SPC/Boa Vista) e protesto | Só registro **manual**, e só dentro do workflow jurídico | Não existe negativação em massa como etapa de régua [F] |
| Acordos com parcelamento, quebra e renegociação | **Sim** | [F] |
| Metas e remuneração | **Sim** (motor completo) | [F] |
| Roteirização de carteiras / filas / score | **Sim** | [F] |
| Relatórios e prestação de contas ao credor | **Sim** (demonstrativo, posição do credor, API) | Sem acesso do credor para aceite [F] |
| Portal do devedor | **Sim**, só Pix | [F] |
| LGPD (titular, retenção, base legal, minimização) | **Sim** | [F] |
| Homologação de templates WhatsApp | **Registro manual** da aprovação | Sem envio/status pela API de templates da Meta [F] |
| Consentimento / opt-out | **Sim** | [F] |
| Horário e frequência de contato | **Sim** (porteiro único p/ todos os canais e IA) | [F] |
| Backoffice de devoluções | **Parcial** | Títulos devolvidos via carga [F]; devolução Pix manual [F] |
| Enriquecimento / localização de contatos (skip tracing) | **Não** | Nenhuma porta [F] |
| Gestão de reclamações (Procon, consumidor.gov, Bacen/RDR, Reclame Aqui) | **Não** | Só como gatilho do assistente e ocorrência de conduta no incentive [F] |
| Monitoria de ligações (escuta/sussurro/transcrição) | **Não** | Depende do provedor [F] |
| Mídia no WhatsApp (comprovante por foto, QR Code, boleto PDF) | **Não** | [F] `comunicacao.md` §9 |

---

## 3. Gaps para produção (versão inicial)

### 3.1 Bloqueadores

Sem estes itens uma assessoria real não deveria operar (risco legal, de segurança ou impossibilidade operacional).

| # | Gap | Por que bloqueia | Evidência | Esforço | Aprovação |
| --- | --- | --- | --- | --- | --- |
| B1 | **Telefonia homologada** (conta + número brasileiro na Twilio ou Plivo, roteiro dos anexos C/D, texto do aviso de gravação e da mensagem de abandono validados) | Call center é o canal principal de qualquer assessoria; código pronto, mas nunca ligou de verdade | issue #12 (comentário de 26/10), `telefonia.md` §12 | M | **Precisa de aprovação** (contrato/custo do provedor; transferência internacional da voz) |
| B2 | **Provedor de SMS** (adaptador do `ChannelGateway`) | Régua de varejo/telecom/financeira no Brasil usa SMS como canal de massa e de aviso com link; o portal e o código de acesso dependem dele para quem não tem WhatsApp | `ChannelAccountService.java:76`; issue #12 | P–M (adaptador) | **Precisa de aprovação** (Zenvia/Infobip/Twilio etc.) |
| B3 | **Boleto registrado** (adaptador `BoletoProvider`) **ou**, no mínimo, **leitor de retorno CNAB 240/400** para o boleto emitido pelo credor | Bancos, educação e condomínios exigem boleto; parte relevante dos devedores não usa Pix [S]; sem CNAB, a conciliação de boleto vira conversão manual para CSV | `pagamentos.md` §14; decisão 79 | Boleto: M · CNAB 240 Febraban (segmentos T/U): M | Boleto: **precisa de aprovação** (#12). CNAB: não precisa (é layout Febraban), mas a decisão 79 o adiou — pede decisão do responsável |
| B4 | **Validação jurídica antes de falar com devedor real**: templates (`templates-cobranca.md`), aviso de privacidade, roteiro/gatilhos do assistente, retenção de gravações, prazos do DPO (decisão 901) | CDC art. 42/71 e LGPD: texto de cobrança é o maior vetor de reclamação e de ação | `templates-cobranca.md` ("para revisão do jurídico"); `assistente-ia.md` §9; `telefonia.md` §12 | — (processo) | Decisão do responsável |
| B5 | **Pré-requisitos de go-live ainda abertos**: custódia das chaves de criptografia (472 — perda = dados pessoais ilegíveis), restauração do RDS ensaiada (473), alarmes notificando alguém (SNS #7 ou plantão), domínio + TLS (certificado importado + CNAME é caminho sem aprovação) | Itens marcados no próprio checklist de go-live | `ativacao-aws.md` §10, §12 | P (cada) | SNS/ACM/Route 53: **precisa de aprovação** (há alternativa sem aprovação para TLS/DNS) |
| B6 | **Chamados do chat de suporte em repositório público** | Texto mascarado de usuários de cliente real indo para issue pública; o próprio desenho pede migrar para privado | `chat-suporte.md` §13; `gh repo view` → `PUBLIC` | P | Decisão do responsável (privatizar ou desligar `helpdesk_tickets_enabled`) |
| B7 | **Antivírus de arquivos desligado por padrão** (ClamAV, decisão 816) com evidências recebidas de credores e de e-mail | Arquivos de terceiros em produção sem varredura | decisão 816 **[revisar]** | P (infra) | **Precisa de aprovação** (daemon nas EC2 ou serviço equivalente) |
| B8 | **Teste de intrusão** antes da produção | Listado no checklist de segurança | `ativacao-aws.md` §9 item 16 | — | **Precisa de aprovação** (fornecedor/custo) |

> **Nota da consolidação (B6):** o repositório público para os chamados foi aceito pelo responsável até a migração para privado (`chat-suporte.md` §5), e a trava `HELPDESK_GITHUB_REQUIRE_PRIVATE` já existe (recusa repositório público quando ligada). O item é de go-live — migrar e ligar a trava, ou manter `helpdesk_tickets_enabled = false` —, não uma falha do código.

Observação: as **funções de IA** (assistente, chat de suporte, Importação PDF com IA) estão todas bloqueadas pela
**decisão 371** (transferência internacional — nenhum Claude 5.5 processa no Brasil). Não bloqueia a operação (tudo
cai para humanos), mas bloqueia o diferencial do produto. Ver §4.1.

### 3.2 Importantes (dia 1–90)

| # | Gap | Valor | Evidência | Esforço | Aprovação |
| --- | --- | --- | --- | --- | --- |
| I1 | **Mídia no WhatsApp**: receber comprovante (foto/PDF) e salvar no S3 com antivírus; enviar QR Code do Pix e PDF do termo/boleto | Devedor manda comprovante por foto o tempo todo; hoje chega só a legenda — perde-se a prova do pagamento [S: prática de mercado] | `comunicacao.md` §9; `assistente-ia.md` §9 | M | Não (Cloud API já aprovada); depende de B7 para varredura |
| I2 | **Negativação em massa como etapa de régua** (comunicação prévia de 10 dias, inclusão, baixa em 5 dias úteis após pagamento — regras já parametrizadas no legal) | Alavanca de recuperação mais usada no varejo/financeiro; hoje só manual e dentro de um caso jurídico | `juridico.md` (`creditBureauNoticeDays`, `removalDeadlineBusinessDays`); issue #13 | G | **Precisa de aprovação** (contrato com birô) |
| I3 | **Protesto eletrônico** (CENPROT/IEPTB) | Recuperação e interrupção da prescrição | issue #13 | G | **Precisa de aprovação** (convênio) |
| I4 | **Enriquecimento/higienização de contatos** (porta + provedor: telefones e e-mails atualizados, *hot number*) | Principal alavanca de CPC (contato com a pessoa certa) em carteira antiga | Nenhuma porta no código [F] | M | **Precisa de aprovação** (bureau de dados; base legal LGPD) |
| I5 | **Gestão de reclamações** (Procon, consumidor.gov.br, Bacen/ouvidoria, Reclame Aqui): registro, prazo, responsável, resposta, vínculo ao caso, indicador por credor | Exigência dos credores regulados (bancos) e risco de descredenciamento [S] | Só gatilho `COMPLAINT` no assistente e `ConductOccurrence` no incentive [F] | M | Não |
| I6 | **Marcador de vulnerabilidade/superendividamento** no devedor (Lei 14.181/2021, CDC art. 54-A e 104-A): restrição/flag, roteamento sênior, bloqueio de oferta que comprometa o mínimo existencial, registro da audiência de repactuação | O assistente já detecta, mas não há onde guardar nem regra que reaja fora da conversa | `RestrictionType` sem tipo; `HandoffReason.VULNERABILITY` só no assistente [F] | M | Não (decisão de produto + jurídico) |
| I7 | **Homologação de templates WhatsApp pela API da Meta** (envio para aprovação, webhook de status, pausa por qualidade) | Hoje o gestor registra à mão a aprovação; template reprovado/pausado só aparece como falha de envio | `WhatsAppCloudGateway` só usa `/messages` [F] | M | Não |
| I8 | **Ação `OFFER` da régua** (adaptador do `CampaignOfferPort`: oferta + link/Pix na etapa) | Régua com oferta e link de pagamento é o padrão de conversão digital | `campanhas.md` §11; nenhuma implementação da porta [F] | M | Não (depende de "sujeito de alçada de campanha" no negotiation) |
| I9 | **E-mail com bounce/reclamação** (SES, #9) e **envio fora de transação** no communication (TEL-02) | Reputação do domínio; robustez em volume | `comunicacao.md` §9 | SES: P · TEL-02: M | SES: **precisa de aprovação** |
| I10 | **Monitoria ao vivo (escuta/sussurro) e transferência de chamada** | Rotina diária do supervisor de call center | `telefonia.md` §12 | M | Depende do provedor escolhido |
| I11 | **Acesso do credor** ao demonstrativo (aceite/contestação) e envio automático | Hoje o ciclo com o credor é fora do sistema | `prestacao-de-contas.md` §13 | M | Não |
| I12 | **Proteção de borda e HA**: WAF (#5), Multi-AZ/ASG ≥ 2 (fase 2), teste de carga (não há nenhum no repositório) | Cliente pagante + webhooks públicos + portal público | `ativacao-aws.md` §1.7; busca por gatling/k6/jmeter vazia [F] | M | WAF/Multi-AZ: **precisa de aprovação** (custo) |
| I13 | **CI/CD** (#10) | Releases e rotinas mensais hoje são manuais | decisão 474 | M | **Precisa de aprovação** |
| I14 | Contestação aberta pelo portal → contestação no collection; boleto/link no portal | Prazo CDC começa quando o devedor contesta | `portal.md` §9 | P | Não (boleto depende de B3) |

### 3.3 Diferenciais (pós-lançamento)

| # | Item | Evidência | Esforço | Aprovação |
| --- | --- | --- | --- | --- |
| D1 | Webhooks de saída assinados na API pública | issue #20 | M | Não |
| D2 | Integrações jurídicas automáticas (DataJud gratuito primeiro; publicações; gravames; registro de imóveis) | issue #13 | M–G | DataJud: cadastro CNJ; demais: **aprovação** |
| D3 | Assistente em voz (URA conversacional) | `assistente-ia.md` §3 ("fase futura") | G | **Aprovação** |
| D4 | Custo de canal (tarifa por mensagem/minuto) no custo por real recuperado | `analytics.md` §10 | P | Não |
| D5 | Emissão de NFS-e / cobrança automática das assessorias pela plataforma | decisão 756 | M | **Aprovação** |
| D6 | Devolução Pix iniciada pelo sistema | `pagamentos.md` §14 | P | Não |
| D7 | Tempo real (SSE) no painel do discador | decisão 171 | P | Não |
| D8 | Tela de metas de SLA da caixa de entrada; SLA em horário comercial | `comunicacao.md` §9 | P | Não |
| D9 | OCR externo para PDF digitalizado (Textract) | issue #23 | M | **Aprovação** |

---

## 4. IA

### 4.1 O que já existe [F]

| Uso | Onde | Modelo | Situação |
| --- | --- | --- | --- |
| **Assistente de negociação** (WhatsApp): identidade, intenção, oferta do motor, proposta, formalização, Pix na conversa, transferência | `assistant` (`TurnOrchestrator`, `HandoffDetector`, `ResponseGuard`, `ModelTierSelector`) | Haiku 5.5 (conversa) / Sonnet 5.5 (negociação, aceite, Pix) via Converse API | Código + telas + painel de qualidade prontos; **desligado** até a decisão 371 e a homologação (§6.7) |
| **Copiloto/sombra** do operador (rascunho na conversa com alerta e próxima oferta) | `assistant` → `ConversationUseCases#proposeDraft` | idem | idem |
| **Chat de suporte** ao usuário interno (manual + consultas só leitura como o próprio usuário + chamado mascarado) | `helpdesk` | Haiku 5.5 | Pronto; desligado pela 371 |
| **Importação PDF**: proposta do mapa de extração (texto com dígitos trocados por 9) e leitura visual de PDF escaneado | `ingestion` (`PdfMapProposer`, `BedrockPdfModel`) | Sonnet | Pronto; desligado por padrão |
| **Score de propensão** (estatístico, não generativo) | `scoring` | scorecard / regressão logística própria | Pronto |

Fundação técnica reutilizável [F]: cliente compartilhado `shared.ai` (`LanguageModel`, `BedrockConverseModel` com
prazo, retentativa, tokens e custo), papel IAM só com `bedrock:InvokeModel`, negação do roteamento global, guarda de
transferência internacional no Terraform e na subida, simuladores para sandbox, minimização por referências opacas,
`PersonalDataRedactor`, trilha cifrada com retenção, amostragem para revisão humana.

Princípios já estabelecidos que toda ampliação deve seguir [F]: "a IA conversa, o motor decide" (valores só do motor);
dados pessoais nunca em logs/eventos/métricas; conteúdo de mensagens **não pode** alimentar o score (`score-propensao.md`
§4 — mudar exige código, revisão e doc); ações sensíveis com humano; registro de invocação do Bedrock desligado.

**Pré-requisito comum a tudo abaixo:** decidir a 371 (opção A — EUA — ou B — global) e registrar a base contratual da
transferência; fazer o roteiro de homologação; e implementar as **conversas de referência** (avaliação automática a cada
mudança de prompt/modelo/política — pendente em `assistente.md` §8 e `chat-suporte.md` §13). Esforço P–M. Sem isso,
nenhuma oportunidade de IA chega a produção.

### 4.2 Oportunidades, ranqueadas por valor / esforço / risco

Ranking [S] — juízo de PO; métricas de ganho são hipóteses a medir com A/B (o `campaign` já tem experimentos).

| # | Oportunidade | Onde / perfil | Ganho (métrica) | Riscos e mitigação | Dados | Esforço |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **Classificação automática das mensagens recebidas** nas conversas de humanos/fila: promessa (com data), alegação de pagamento, contestação/"não reconheço", óbito, número errado/terceiro, opt-out, vulnerabilidade, Procon/advogado | Caixa de entrada; operador e supervisor | Tempo de 1ª resposta (SLA já medido), conversas roteadas certo de primeira, opt-out/óbito/terceiro tratados no mesmo minuto (compliance) | Falso negativo em opt-out → manter a regra determinística (`HandoffDetector`) como piso; óbito/contato errado/opt-out só como **sugestão de tabulação** que o humano confirma (a tabulação já tem ação automática, decisões 776–780) | Texto mascarado das últimas mensagens; nada vai para o score | **M** (reaproveita `register_intent` e o catálogo de motivos) |
| 2 | **Leitura de comprovante de pagamento** (foto/PDF recebido no WhatsApp ou anexado pelo operador) → pré-preenche o "pagamento externo informado" (valor, data, recebedor, id fim a fim/autenticação) | Inbox e ficha; operador → fila de revisão segregada que já existe | Tempo de conciliação; pagamentos em revisão parados; quebra indevida evitada (a suspensão da quebra já existe) | **Fraude por comprovante falso**: nunca baixa automática — sempre a revisão segregada; conferir id fim a fim no PSP quando for Pix para a conta do credor; imagem contém dados do pagador (minimizar: só campos extraídos) | Imagem → Sonnet (multimodal, já usado no PDF) | **M** (depende de I1 — mídia) |
| 3 | **Resumo do caso** ao abrir a ficha e **resumo da transferência** (o que aconteceu desde o último contato, promessas, contestações, tom do devedor, o que já foi oferecido) | Estação de cobrança (ficha `GET /cases/{id}/sheet`); operador | TMA ↓; conversão CPC → acordo ↑; menos repergunta ao devedor | Alucinação → resumo gerado só de fatos estruturados + texto mascarado, cada frase com referência à interação; nunca mistura credores fora do escopo; guardar sem dados pessoais | Interações, conversas, promessas, contestações do caso | **P–M** |
| 4 | **Mapeamento automático de colunas na importação CSV/XLSX** (mesmo padrão do PDF: amostra com dígitos mascarados → mapa → validação → modelo salvo por credor) e alertas de saneamento (colunas de juros, encargos, contratos repetidos) | Importação; backoffice/gestor | Tempo de entrada de carteira nova (onboarding de credor), erros de carga | Baixo — mapa validado no servidor, conferência humana já existe | Cabeçalhos + amostra mascarada | **P** |
| 5 | **Triagem de contestações**: tipo e alegação estruturada, documentos a pedir ao credor, rascunho da resposta ao devedor (dentro do template de serviço), **leitura da resposta do credor por e-mail** sugerindo a posição (procedente/improcedente) para o humano confirmar | Fila de contestações; analista/jurídico | Prazo CDC cumprido; tempo por contestação; ajuste sugerido mais rápido | Risco jurídico → só rascunho e sugestão; confirmação humana já é obrigatória (`…/confirm`); anexos só depois do antivírus | Descrição da contestação, e-mail do credor (já cifrados) | **M** |
| 6 | **QA automática de 100% das conversas de texto** (humanas e do assistente) contra o formulário de monitoria do incentive: conduta CDC art. 42 (ameaça, constrangimento, terceiro), script, oferta fora de ordem | Qualidade/supervisor; monitoria | Cobertura de monitoria de amostra para 100%; reclamações e Procon ↓ | **Trabalhista**: a nota oficial e o portão de monitoria do variável continuam humanos — a IA só prioriza o que revisar e sugere a ocorrência; transparência aos operadores | Texto das conversas | **M** |
| 7 | **Indicadores em linguagem natural** no chat de suporte (ferramenta só leitura sobre `AnalyticsQueries`) + **copiloto do supervisor** (resumo diário: filas e SLA estourados, abandono do discador, promessas e contestações a vencer, operadores fora da curva) | Painéis/tela inicial; gestor e supervisor | Tempo de análise; reação no mesmo dia | Números só das ferramentas (mesmo princípio do motor); escopo por credor do usuário já aplicado pelo helpdesk | Agregados, sem dado pessoal | **P–M** |
| 8 | **Assistente em mais canais**: chat no portal (devedor já autenticado) e e-mail | Portal; devedor | Autoatendimento ↑, custo por acordo ↓ | Mesmos guardrails; o portal resolve a identidade | — | **M** |
| 9 | **Detecção de vulnerabilidade/superendividamento** fora do assistente (inbox, transcrições) gravando o marcador de I6 | Inbox/ficha; operador sênior | Conformidade com a Lei 14.181; menos reclamação | Dado sensível (saúde, LGPD art. 11) → guardar só a categoria, nunca o texto; decisão humana para marcar | Texto mascarado | **M** (depende de I6) |
| 10 | **Transcrição e QA de ligações** (resumo pós-chamada, tabulação sugerida, conduta) | Telefonia/monitoria | Tabulação mais correta, TMA pós-chamada ↓, monitoria 100% em voz | Gravação é dado sensível com permissão própria; transcrição por Amazon Transcribe ou pelo provedor = **serviço novo → precisa de aprovação (issue)**; transferência internacional se fora do Brasil | Áudio das gravações | **G** |
| 11 | **Melhor canal e horário de contato** (modelo estatístico por canal × faixa horária, não LLM) | Discador/régua; automático | CPC ↑; tentativas por CPC ↓; menos contato inútil (porteiro) | Viés e LGPD: proibido usar DDD/localização (lista fechada) — exige mudar `score-propensao.md` §4 e fazer RIPD; respeita sempre o porteiro | Horários de resposta/atendimento (fatos já no scoring) | **G** |
| 12 | **Proposta inicial ótima dentro da alçada** (começar a escada no degrau certo por segmento) | Simulador/assistente | Desconto médio ↓ com conversão igual | **Isonomia**: a decisão do produto rejeita condição por quem atende; só por segmento da política e com A/B do campaign; nunca por perfil individual sensível | Histórico de acordos por segmento | **G** |

**Ordem sugerida de entrega [S]:** fundação (371 + homologação + conversas de referência) → 4 e 3 (baratos, baixo risco,
ganham confiança) → 1 → 2 (junto com mídia) → 5 → 6/7 → 8/9 → 10–12.

### 4.3 Guardas obrigatórias para qualquer nova feature de IA

1. Porta própria no módulo dono (`application.port.out`), cliente de `shared.ai`; simulador no sandbox; desligado por padrão.
2. Contexto minimizado (referências opacas, `PersonalDataRedactor`), nunca documento/contato/endereço; prompt versionado com SHA-256 na trilha.
3. Saída estruturada por ferramenta + validação no servidor; valores e decisões financeiras sempre de motor/regra.
4. Sugestão → humano confirma em tudo que é irreversível ou sensível (opt-out, óbito, contato inválido, baixa, contestação, monitoria oficial).
5. Métricas e eventos só com códigos/contagens/custo; avaliação por conversas de referência; orçamento e limite de custo por assessoria.
6. Documentar evento/métrica nova em `logs-e-metricas.md` e a decisão em `decisoes-autonomas.md`.

---

## 5. Resumo para o responsável

- **Produto funcionalmente amplo; gargalo é integração e go-live.** 23 módulos, quase todos completos; os parciais são
  exatamente os que dependem de provedor (SMS, boleto, telefonia homologada, birôs/protesto) — todos já na issue #12/#13.
- **Bloqueadores:** telefonia homologada, SMS, boleto ou CNAB, validação jurídica dos textos, itens abertos do checklist de
  go-live (chaves, restauração, alarmes, TLS), repositório público dos chamados, antivírus, pentest.
- **IA:** a base é boa e segura, mas **nada de IA liga em produção sem a decisão 371**. As próximas oportunidades com melhor
  relação valor/risco são classificação da inbox, leitura de comprovante, resumo do caso e mapeamento de importação.
- Pendências desatualizadas nos desenhos (`pagamentos.md` §14, `campanhas.md` §11) merecem limpeza.
