# ADR-0012 — Anonimização do titular pelo módulo dono de cada dado

**Situação.** Aceita (24/11/2026). Nasce da correção do achado SEG-20261124-D01 da
[auditoria de segurança de 24/11](../../tecnico/seguranca-auditorias/2026-11-24.md); pareceres do `arquiteto` e do
`engenheiro-seguranca`; decisões 1454–1459.

**Contexto.** O pedido de eliminação do titular (LGPD art. 18) e a retenção `DEBTOR_INACTIVE` anonimizam o devedor no
módulo `debt`, que publica `DebtorAnonymized` (só ids). Os dados pessoais do titular, porém, estão espalhados por
vários módulos (comunicação, pagamentos, cobrança, jurídico, importação…), várias tabelas são imutáveis por gatilho, há
arquivos no armazenamento (fora da transação do banco) e há chaves estrangeiras de outros módulos para dados do
`debt` — a exclusão física dos contatos barrava a anonimização de todo devedor já contatado.

**Decisão.**

1. O `debt` anonimiza o cadastro e publica `DebtorAnonymized`; **cada módulo que guarda dado do titular consome o
   evento** (`TenantEventListener`, consumidor `<modulo>.debtor-anonymized`, idempotente e transacional — ADR 0004) e
   apaga o que é dele, com auditoria e métrica sem dado pessoal. Nenhum anonimizador central atravessa schemas
   (ADR 0001).
2. Fica o que sustenta prestação de contas, indicadores e cadeia de custódia (valores, datas, situação, hashes de
   arquivos); saem texto livre, contato e arquivo.
3. Registro referenciado por outros módulos é **neutralizado no lugar**, nunca apagado, com estado explícito (ex.:
   `debtor_contact.anonymized_at` com `check`), recusa de toda alteração e fora dos `port/in` de uso operacional.
4. Tabela imutável ganha **exceção estreita no gatilho** (`<tabela>_anonymize_only`: só "texto livre → nulo", exige o
   valor anterior preenchido, compara o resto por `to_jsonb`), com `grant update` só nessas colunas e nas de versão;
   `DELETE` continua proibido. Padrão das migrations `V0751`, `V1093` e `V0811`.
5. Blind index substituído por um valor próprio do registro, não reproduzível a partir de um contato real.
6. Arquivos com dado pessoal são apagados **dentro** da transação do consumo, depois de gravar o banco: a falha desfaz
   tudo e o evento é reprocessado (exclusão idempotente).
7. Cada módulo novo com dado do titular precisa do consumidor e de um IT; a cobertura fica em `seguranca.md` §3.

**Alternativas.** Exclusão em cascata (perde indicadores e prestação de contas); `on delete set null` (esbarra nos
gatilhos de imutabilidade); revogar a imutabilidade; anonimizador central em `shared` (viola as fronteiras dos módulos).

**Consequências.** Quem consome os `port/in` tolera o registro neutralizado ("Contato removido"). Pendente de decisão
do responsável: versões antigas de arquivos apagados no S3 versionado (SEG-20261124-D01d) e o arquivo bruto do extrato
importado (D01e). Devedores anonimizados antes desta ADR não são reprocessados (sem produção ainda).
