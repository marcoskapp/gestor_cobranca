# Modelos iniciais de mensagens de cobrança (templates)

> **Status: proposta para revisão do jurídico.** Os textos abaixo são ponto de partida. Antes de cadastrar, o jurídico
> revisa linguagem, base legal e identificação; depois, os de WhatsApp são submetidos à aprovação da Meta.
> Desenho técnico dos templates: [tecnico/desenho/comunicacao.md](../tecnico/desenho/comunicacao.md) §2.
> Uso nas réguas: [tecnico/desenho/campanhas.md](../tecnico/desenho/campanhas.md) §3.

## 1. Regras que todos os modelos seguem

**Legais e de boa prática (Brasil)**

| Regra | Por quê | Como os modelos aplicam |
| --- | --- | --- |
| Sem constrangimento, ameaça ou exposição ao ridículo | CDC art. 42 (e art. 71) | Tom neutro e respeitoso; nada de "último aviso", "providências judiciais", "nome sujo" ou prazos ameaçadores |
| Não revelar a dívida a terceiros | CDC art. 42, LGPD (necessidade e segurança) | O **primeiro contato** com número não confirmado não cita credor nem valor; os detalhes vêm depois da confirmação de identidade (o assistente e o operador fazem essa confirmação na conversa) |
| Dados mínimos na mensagem | LGPD art. 6º, III | Só primeiro nome; nunca CPF, endereço ou lista de títulos. O sistema recusa variável com documento, e-mail ou telefone |
| Identificação de quem cobra | Transparência; CDC art. 42-A para documentos de cobrança | Nome da assessoria em toda mensagem; credor e CNPJ nos modelos com valor e no rodapé dos e-mails (revisar com o jurídico) |
| Opção de não receber | Opt-in/opt-out (Meta e LGPD) | Toda mensagem de abertura termina com "responda SAIR". O sistema reconhece SAIR, PARAR, CANCELAR, "não quero mais receber" etc. e registra a restrição de contato na hora |
| Negativação só com autorização | Política do credor (decisões do assistente) | Nenhum modelo menciona cadastro de inadimplentes |
| Horário e frequência | CDC e política de contato | Não está no texto: o sistema já bloqueia envio fora do horário legal, em feriados e acima do limite de tentativas |

**Da Meta (WhatsApp)**

* Mensagem que **inicia** contato (ou depois de 24 h sem resposta do devedor) só por template aprovado.
* Categoria pretendida: **Utilidade** (lembretes e avisos sobre uma relação existente). Texto promocional ("aproveite",
  "imperdível", "só hoje") faz a Meta reclassificar como **Marketing**, que custa mais e tem mais restrições.
* Sem encurtador de link; links só do domínio próprio da assessoria (o sistema só aceita `https`).
* Códigos de acesso usam a categoria **Autenticação**, com formato fixo da Meta: corpo padrão, um único parâmetro (o
  código) e botão "copiar código" (ver §5.1). No sistema, a categoria é informada no template (**Categoria na Meta**).

**Do sistema**

* Variáveis no texto como `{{nome}}`; toda variável usada é declarada e toda declarada é usada; no WhatsApp a ordem
  dos parâmetros segue a ordem de declaração (a tabela de cada modelo já está na ordem certa).
* Código do template: maiúsculas, números e `_` (`COBRANCA_ABERTURA`). Nos templates do portal o sufixo é o canal
  (`_WHATSAPP`, `_SMS`, `_EMAIL`).
* Nas réguas, cada variável recebe uma **origem**: `DEBTOR_FIRST_NAME`, `OPEN_BALANCE`, `ELIGIBLE_BALANCE`,
  `OLDEST_DUE_DATE`, `DAYS_OVERDUE`, `CREDITOR_NAME`, `OFFER_AMOUNT`, `OFFER_DUE_DATE`, `PAYMENT_LINK`,
  `NEXT_INSTALLMENT_AMOUNT`, `NEXT_INSTALLMENT_DUE_DATE` (próxima parcela em aberto do acordo em vigor do caso),
  `PORTAL_LINK`, `PORTAL_LINK_HOURS` (link de uso único do portal, gerado no envio, e sua validade) ou `LITERAL`.
  Sem acordo em vigor, sem parcela em aberto ou sem portal para a carteira do caso, a etapa é **pulada** com o motivo
  (`NO_ACTIVE_AGREEMENT`, `NO_OPEN_INSTALLMENT`, `PORTAL_UNAVAILABLE`) — nunca sai mensagem com valor vazio.
* Troque `[NOME DA ASSESSORIA]` pelo nome fantasia antes de submeter (não há variável para o nome da assessoria; o
  template é da assessoria).

## 2. Qual modelo usar em cada situação

| Situação | Número confirmado do devedor?¹ | Modelo |
| --- | --- | --- |
| Primeiro contato, base nova | Não | `COBRANCA_ABERTURA` |
| Primeiro contato | Sim | `COBRANCA_ABERTURA_CREDOR` |
| Sem resposta depois do primeiro contato | Qualquer | `COBRANCA_RETOMADA` |
| Proposta pronta (etapa de oferta da régua) | Sim | `COBRANCA_PROPOSTA` ou `COBRANCA_PROPOSTA_LINK` |
| Informar saldo e atraso | Sim | `COBRANCA_SALDO` |
| Lembrete de parcela do acordo (antes do vencimento) | Sim | `ACORDO_LEMBRETE_PARCELA` |
| Convite para o portal de autonegociação pela régua | Sim | `COBRANCA_PORTAL_LINK` |
| Portal de autonegociação (exigidos pelo sistema) | — | `PORTAL_CODIGO_*`, `PORTAL_LINK_*`, `PORTAL_ATENDIMENTO_*` |

¹ "Confirmado" = o devedor já foi identificado nesse número (contato efetivo anterior ou identidade confirmada numa
conversa). Na régua, use o filtro de segmento "já houve contato efetivo".

Régua sugerida para começar (WhatsApp): **D0** `COBRANCA_ABERTURA` (ao responder → entregar ao assistente) →
**D+3** sem resposta `COBRANCA_RETOMADA` → **D+7** sem resposta, tarefa de ligação para a equipe.

Régua de **lembrete de parcela** (campanha com finalidade "acompanhamento de acordo", que não pausa por acordo nem por
pagamento): segmento com **Acordo em vigor = sim** e **Parcela do acordo vence em (dias)** de 3 a 3 (vence daqui a 3 dias),
reinscrição após 20 dias (uma inscrição por parcela mensal) e uma etapa `ACORDO_LEMBRETE_PARCELA` no horário comercial.
A régua lê o valor e o vencimento no momento do envio: parcela paga antes da etapa → o caso sai do segmento e a etapa
não sai.

## 3. WhatsApp — abertura e retomada

### 3.1 `COBRANCA_ABERTURA` — primeiro contato, sem expor a dívida
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}! Aqui é da [NOME DA ASSESSORIA]. Temos um assunto do seu interesse para tratar com você.
> Para proteger seus dados, só passamos os detalhes depois de confirmar que estamos falando com a pessoa certa.
> Podemos continuar por aqui?
>
> Se não quiser receber nossas mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome (`FIRST_NAME`) | `DEBTOR_FIRST_NAME` |

### 3.2 `COBRANCA_ABERTURA_CREDOR` — primeiro contato com número confirmado
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}! Aqui é da [NOME DA ASSESSORIA], assessoria autorizada por {{credor}}.
> Queremos conversar sobre uma pendência financeira e apresentar opções para você regularizar.
> Podemos continuar por aqui?
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |
| `credor` | Texto curto (`TEXT`) | `CREDITOR_NAME` |

### 3.3 `COBRANCA_RETOMADA` — sem resposta
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}. Tentamos falar com você nos últimos dias sobre um assunto do seu interesse.
> Quando for melhor para você, é só responder esta mensagem que seguimos por aqui.
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |

## 4. WhatsApp — com valores (só para número confirmado)

### 4.1 `COBRANCA_SALDO` — saldo e atraso
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}. Aqui é da [NOME DA ASSESSORIA], em nome de {{credor}}.
> Consta em aberto o valor de {{saldo}}, com {{dias}} dias de atraso.
> Podemos ajudar a encontrar a melhor forma de regularizar. Responda esta mensagem para conversar.
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |
| `credor` | Texto curto | `CREDITOR_NAME` |
| `saldo` | Valor (`MONEY`) | `OPEN_BALANCE` |
| `dias` | Número inteiro (`INTEGER`) | `DAYS_OVERDUE` |

### 4.2 `COBRANCA_PROPOSTA` — proposta da etapa de oferta
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}. A [NOME DA ASSESSORIA], em nome de {{credor}}, preparou uma proposta para você regularizar sua
> pendência: pagamento de {{valor}} até {{vencimento}}.
> Quer saber os detalhes ou conhecer outras opções de parcelamento? É só responder esta mensagem.
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |
| `credor` | Texto curto | `CREDITOR_NAME` |
| `valor` | Valor | `OFFER_AMOUNT` |
| `vencimento` | Data (`DATE`) | `OFFER_DUE_DATE` |

### 4.3 `COBRANCA_PROPOSTA_LINK` — proposta com link de pagamento
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}. A [NOME DA ASSESSORIA], em nome de {{credor}}, preparou uma proposta para você regularizar sua
> pendência: pagamento de {{valor}} até {{vencimento}}.
> Para pagar, acesse: {{link}}
> Dúvidas ou outras opções? É só responder esta mensagem.
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |
| `credor` | Texto curto | `CREDITOR_NAME` |
| `valor` | Valor | `OFFER_AMOUNT` |
| `vencimento` | Data | `OFFER_DUE_DATE` |
| `link` | Link (`URL`, só `https`) | `PAYMENT_LINK` |

Link em mensagem de cobrança é alvo de golpes: use sempre o domínio próprio e oriente na comunicação institucional que
a assessoria nunca pede senha nem dados de cartão.

### 4.4 `ACORDO_LEMBRETE_PARCELA` — lembrete de parcela do acordo
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}. Aqui é da [NOME DA ASSESSORIA], em nome de {{credor}}.
> Lembramos que a parcela do seu acordo, no valor de {{valor}}, vence em {{vencimento}}.
> Se já pagou, desconsidere esta mensagem. Dúvidas? É só responder por aqui.
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |
| `credor` | Texto curto | `CREDITOR_NAME` |
| `valor` | Valor | `NEXT_INSTALLMENT_AMOUNT` (valor ainda devido da parcela) |
| `vencimento` | Data | `NEXT_INSTALLMENT_DUE_DATE` |

Tom de lembrete, sem ameaça nem consequência ("evite a quebra do acordo" não entra). O valor é o que falta pagar da
parcela (parcela paga em parte mostra o saldo dela).

### 4.5 `COBRANCA_PORTAL_LINK` — convite para o portal pela régua
Categoria: Utilidade · Idioma: pt_BR

> Olá, {{nome}}. Aqui é da [NOME DA ASSESSORIA], em nome de {{credor}}.
> Você pode consultar suas condições e negociar com segurança pelo nosso portal: {{link}}
> O link é pessoal, só pode ser usado uma vez e vale por {{horas}} horas.
>
> Para não receber mais mensagens, responda SAIR.

| Variável | Tipo | Origem na régua |
| --- | --- | --- |
| `nome` | Primeiro nome | `DEBTOR_FIRST_NAME` |
| `credor` | Texto curto | `CREDITOR_NAME` |
| `link` | Link | `PORTAL_LINK` |
| `horas` | Número inteiro | `PORTAL_LINK_HOURS` |

O link é **gerado no envio** pelo portal, com as mesmas regras do link enviado pelo operador: uso único, código de acesso
no fragmento do endereço (não vai para logs), novo link anula o anterior e o envio fica na trilha de acesso. Só
funciona para carteiras habilitadas no portal do credor e devedores com dívida nelas; fora disso a etapa é pulada
(`PORTAL_UNAVAILABLE`). O mesmo par `link`/`horas` pode entrar no lembrete de parcela (§4.4) para o devedor pagar a
parcela pelo portal.

## 5. Templates exigidos pelo portal de autonegociação

O portal envia estes templates sozinho; os nomes são fixos (configuração `app.portal.templates`). Cadastre um por canal
habilitado no portal.

### 5.1 `PORTAL_CODIGO_WHATSAPP` — código de acesso
Categoria na Meta: **Autenticação** (no sistema: **Categoria na Meta = Autenticação**)

A Meta fixa o texto do template de autenticação; na criação, escolha o idioma `pt_BR`, marque o aviso de segurança e
informe a validade do código (10 minutos, a mesma de `app.portal.access.code-ttl`) e o botão **copiar código**. O texto
fica assim:

> {{codigo}} é seu código de verificação. Para sua segurança, não o compartilhe.
> Este código expira em 10 minutos. — botão **Copiar código**

| Variável | Tipo |
| --- | --- |
| `codigo` | Código (`CODE`, até 15 caracteres) |

O template tem **uma única variável**: o sistema recusa template de autenticação com outra variável e envia o código no
corpo e no botão, como a Meta exige. A validade não é variável (é configuração do template na Meta). Nos canais SMS e
e-mail (`PORTAL_CODIGO_SMS`, `PORTAL_CODIGO_EMAIL`) `minutos` continua opcional: o portal só o envia quando o template o
declara.

### 5.2 `PORTAL_LINK_WHATSAPP` — link de acesso enviado pelo operador
Categoria: Utilidade

> Olá! Para ver suas condições de negociação com segurança, acesse o portal da [NOME DA ASSESSORIA]: {{link}}
> O link é pessoal e vale por {{horas}} horas. Se não foi você que pediu, ignore esta mensagem.

| Variável | Tipo |
| --- | --- |
| `link` | Link |
| `horas` | Número inteiro |

### 5.3 `PORTAL_ATENDIMENTO_WHATSAPP` — confirmação de pedido de atendimento
Categoria: Utilidade

> Recebemos seu pedido de atendimento. Protocolo: {{protocolo}}.
> Uma pessoa da nossa equipe vai responder por aqui em breve.

| Variável | Tipo |
| --- | --- |
| `protocolo` | Código |

## 6. E-mail

E-mail sai sempre por template e exige assunto. Rodapé comum (revisar com o jurídico, CDC art. 42-A):

> [NOME DA ASSESSORIA] — CNPJ [CNPJ DA ASSESSORIA] · Cobrança realizada em nome de {{credor}}.
> Esta mensagem é pessoal. Se você não é a pessoa indicada, por favor desconsidere. Para não receber mais e-mails,
> responda com a palavra SAIR.

Nos modelos sem `{{credor}}` (abertura), use a versão do rodapé sem a segunda frase da primeira linha.

| Código | Assunto | Corpo | Variáveis |
| --- | --- | --- | --- |
| `COBRANCA_ABERTURA_EMAIL` | Assunto do seu interesse — [NOME DA ASSESSORIA] | Mesmo texto do §3.1, trocando "por aqui" por "respondendo este e-mail ou pelo WhatsApp" | `nome` |
| `COBRANCA_PROPOSTA_EMAIL` | Proposta para regularização | Texto do §4.2 + rodapé | `nome`, `credor`, `valor`, `vencimento` |
| `PORTAL_CODIGO_EMAIL` | Seu código de acesso | Texto do §5.1 | `codigo`, `minutos` |
| `PORTAL_LINK_EMAIL` | Acesso ao portal de negociação | Texto do §5.2 | `link`, `horas` |
| `PORTAL_ATENDIMENTO_EMAIL` | Recebemos seu pedido de atendimento | Texto do §5.3, trocando "por aqui" por "por e-mail" | `protocolo` |

## 7. SMS (para quando o provedor for contratado — issue #12)

Limite prático de 160 caracteres, sem acentos se o provedor cobrar SMS com acento como mensagem dupla.

| Código | Texto |
| --- | --- |
| `COBRANCA_ABERTURA_SMS` | `[ASSESSORIA]: Ola {{nome}}, temos um assunto do seu interesse. Fale conosco pelo WhatsApp [NUMERO]. Para nao receber, responda SAIR.` |
| `PORTAL_CODIGO_SMS` | `[ASSESSORIA]: seu codigo de acesso e {{codigo}}. Vale por {{minutos}} min. Nao compartilhe.` |
| `PORTAL_LINK_SMS` | `[ASSESSORIA]: acesse suas condicoes de negociacao: {{link}} (pessoal, vale {{horas}}h).` |
| `PORTAL_ATENDIMENTO_SMS` | `[ASSESSORIA]: pedido de atendimento recebido. Protocolo {{protocolo}}.` |

## 8. Como cadastrar

1. Jurídico revisa e aprova os textos (este documento).
2. **Conversas → Templates → Novo**: código, canal, idioma `pt_BR`, credor (vazio = vale para todos os credores da
   assessoria; preencha quando o texto citar um credor fixo), texto e variáveis na ordem das tabelas.
3. WhatsApp: escolha a **Categoria na Meta** (Utilidade; Autenticação só para `PORTAL_CODIGO_WHATSAPP`) e crie o mesmo
   template no gerenciador da Meta (WhatsApp Manager), na mesma categoria, com o mesmo nome no provedor e os
   parâmetros na mesma ordem; quando a Meta aprovar, registre a aprovação no template do sistema e publique. Se a Meta
   reclassificar a categoria, ajuste-a no rascunho antes de publicar.
4. Use **Pré-visualizar** com valores de exemplo antes de publicar.
5. Nas réguas, escolha o template na etapa "Enviar mensagem" e a origem de cada variável.

## 9. Pendências encontradas no sistema

| # | Pendência | Efeito | Proposta |
| --- | --- | --- | --- |
| P1 | ~~Não há origem de variável para **parcela de acordo**~~ — **resolvida em 26/10/2026** | — | Origens `NEXT_INSTALLMENT_AMOUNT` e `NEXT_INSTALLMENT_DUE_DATE` (próxima parcela em aberto do acordo em vigor do caso; sem acordo ou parcela, a etapa é pulada), filtro de segmento "parcela do acordo vence em N dias" e o modelo `ACORDO_LEMBRETE_PARCELA` (§4.4) |
| P2 | ~~Não há origem de variável para o **link do portal**~~ — **resolvida em 26/10/2026** | — | Origens `PORTAL_LINK` e `PORTAL_LINK_HOURS` (link de uso único gerado pelo portal no envio, mesmas regras do link do operador; sem portal para a carteira, a etapa é pulada) e o modelo `COBRANCA_PORTAL_LINK` (§4.5) |
| P3 | ~~Template de **autenticação** da Meta com dois parâmetros~~ — **resolvida em 26/10/2026** | — | Categoria do template na Meta (`UTILITY`, `MARKETING`, `AUTHENTICATION`) no cadastro; template de autenticação com uma única variável (o código), enviado com corpo e botão "copiar código"; o portal manda `minutos` só se o template o declara (§5.1) |
| P4 | Não há variável para o nome da assessoria | O nome vai fixo no texto de cada template | Aceitável; se houver marcas diferentes por credor, criar templates por credor |
