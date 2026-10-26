# Stack tecnológica

Versões fixadas no `backend/pom.xml` e no `frontend/package.json`. Itens marcados como **planejado** entram no código conforme os módulos correspondentes forem concluídos; itens marcados como **requer aprovação** dependem de decisão do responsável pelo projeto (linguagem ou serviço fora do escopo inicialmente autorizado).

## Backend

| Tecnologia | Versão | Finalidade |
| --- | --- | --- |
| Java (OpenJDK / Amazon Corretto) | 21 LTS | Linguagem principal do backend |
| Spring Boot | 4.1.2 | Base da aplicação: configuração, servidor web embutido (Tomcat), injeção de dependências, actuator |
| Spring Modulith | 2.1.2 | Monólito modular: verificação das fronteiras entre módulos e publicação transacional de eventos de integração (*event publication registry*) |
| Spring Web MVC | 7.x | API REST, validação de entrada, respostas de erro RFC 9457 (Problem Details) |
| Spring Security | 7.1 | Autenticação e autorização: JWT RS256, autorização por permissão (`@PreAuthorize`), cabeçalhos de segurança, CORS |
| Spring Security OAuth2 Resource Server + Nimbus JOSE | 7.1 | Emissão e validação de tokens JWT assinados com RSA |
| Spring Data JPA + Hibernate ORM | Hibernate 7.4 | Persistência dos agregados de domínio, lock otimista e pessimista, validação do mapeamento contra o schema |
| Spring JDBC | 7.x | Consultas de leitura e gravações de alto volume (auditoria, logs, relatórios, fila de jobs) |
| Spring Framework Resilience (`@Retryable`) | 7.x | Novas tentativas controladas em falhas transitórias de integração |
| Bean Validation (Hibernate Validator) | 9.x | Validação declarativa das requisições |
| Jackson | 3.1 | Serialização JSON (API, eventos, campos JSONB, logs estruturados) |
| Flyway | 12.4 | Versionamento do schema do banco (migrations SQL por módulo) |
| PostgreSQL JDBC Driver | 42.x | Conexão com o PostgreSQL |
| HikariCP | 6.x | Pool de conexões |
| ShedLock | 7.10.1 | Execução única de tarefas agendadas entre várias instâncias EC2 |
| Micrometer + registry CloudWatch | 1.17 | Métricas técnicas e de negócio publicadas no CloudWatch |
| Logback + formatter JSON próprio | 1.5 | Três fluxos de log estruturado (técnico, negócio, acesso) com mascaramento de dados pessoais |
| AWS SDK for Java v2 (S3, CloudWatch, Bedrock Runtime; cliente HTTP Apache 5) | 2.57.2 | Armazenamento de arquivos no S3 com URLs temporárias assinadas; publicação de métricas; chamadas ao modelo do assistente (Converse API) |
| Bouncy Castle | 1.86 | Implementação do Argon2id para hash de senhas |
| Spring Mail (Jakarta Mail) | — | Envio de e-mails transacionais via SMTP (compatível com Amazon SES) |
| Apache POI | 6.0.0 | Leitura de carteiras em XLSX e exportação de relatórios |
| Apache Commons CSV | 1.14.1 | Leitura de carteiras e arquivos de retorno em CSV |
| Apache PDFBox | 3.0.8 | Geração de termos de acordo e demonstrativos em PDF |
| springdoc-openapi | 3.1.1 | Documentação OpenAPI da API (UI habilitada apenas fora de produção) |

### Recursos de segurança implementados no código (sem dependência externa)

| Recurso | Finalidade |
| --- | --- |
| AES-256-GCM com rotação de chave | Criptografia de campos sensíveis (documento, telefone, e-mail, endereço do devedor; segredo MFA) |
| HMAC-SHA256 (*blind index*) | Busca por CPF/contato sem armazená-los em claro |
| TOTP (RFC 6238) | Autenticação em dois fatores compatível com Google/Microsoft Authenticator |
| UUID v7 | Identificadores ordenáveis no tempo, sem exposição de sequência |
| Cadeia de hash SHA-256 | Integridade verificável da trilha de auditoria |

## Testes e qualidade

| Tecnologia | Versão | Finalidade |
| --- | --- | --- |
| JUnit 5 + AssertJ + Mockito | via Spring Boot | Testes unitários e de integração |
| Spring Security Test | 7.1 | Testes de autorização dos endpoints |
| Spring Modulith Test | 2.1.2 | Verificação automática das dependências entre módulos |
| ArchUnit | 1.5.1 | Regras de arquitetura (camadas, dependências proibidas) executadas no build |
| Testcontainers (PostgreSQL) | 2.x | Testes de integração contra PostgreSQL real, inclusive políticas RLS |

## Frontend

| Tecnologia | Versão | Finalidade |
| --- | --- | --- |
| Angular | 22.x | Aplicação web (SPA) com componentes standalone e signals |
| TypeScript | 5.x | Linguagem do frontend |
| Bootstrap | 5.3 | Framework de interface (parametrizado só por variáveis documentadas, `frontend/src/styles/bootstrap/`) |
| ng-bootstrap | 21.x | Componentes Bootstrap nativos do Angular (diálogos, menus, calendários, abas, avisos), sem o JavaScript do Bootstrap |
| Bootstrap Icons | 1.13 | Ícones em sprite SVG servido pela aplicação |
| Angular CDK | 22.x | Utilitários sem aparência: área de transferência, anúncios para leitor de tela, pontos de quebra, overlay do tour |
| RxJS | 7.x | Fluxos assíncronos e integração HTTP |
| Angular HttpClient + interceptors | 22.x | Autenticação com access token em memória e renovação por cookie HttpOnly |

## Banco de dados

| Tecnologia | Versão | Finalidade |
| --- | --- | --- |
| PostgreSQL (Amazon RDS) | 16 | Banco transacional: um schema por módulo, Row Level Security por tenant, JSONB para atributos flexíveis, fila de jobs com `SKIP LOCKED` |

## Infraestrutura e entrega

| Tecnologia | Finalidade | Situação |
| --- | --- | --- |
| Amazon EC2 (Linux, Auto Scaling Group) | Execução das instâncias da aplicação | Autorizado |
| Elastic Load Balancing (ALB) | Balanceamento HTTPS e health checks | Autorizado |
| Amazon RDS for PostgreSQL | Banco gerenciado com backups automáticos | Autorizado |
| Amazon S3 | Arquivos e artefatos de deploy | Autorizado |
| Amazon CloudWatch (Logs, Metrics, Alarms) + CloudWatch Agent | Logs, métricas, painéis e alarmes | Autorizado |
| Amazon Bedrock (Converse API; Claude Haiku 5.5 e Claude Sonnet 5.5) | Modelo de linguagem do assistente de atendimento e negociação (F24) | Autorizado (25/10/2026, issue #12); residência de dados e transferência internacional a definir pelo responsável **[revisar]** |
| Terraform (HCL) | Infraestrutura como código | Aprovado |
| Docker / Docker Compose | Ambiente local (PostgreSQL, SMTP de teste) | Aprovado |
| Bash | Scripts de bootstrap de instância e deploy | Aprovado |
| SQL | Migrations Flyway | Aprovado |
| GitHub | Repositório e pull requests | Em uso |
| AWS Certificate Manager | Certificado TLS do ALB (sem custo) | Requer aprovação |
| AWS Systems Manager Parameter Store + KMS | Guarda de segredos (senhas do banco, chaves JWT e de criptografia) | Requer aprovação |
| AWS WAF | Proteção contra ataques web e limite de taxa no ALB | Requer aprovação (recomendado) |
| Amazon SES | Envio de e-mails | Requer aprovação (alternativa: SMTP próprio) |
| GitHub Actions | Build, testes e publicação do artefato | Requer aprovação |

## Integrações externas

| Integração | Padrão adotado | Situação |
| --- | --- | --- |
| WhatsApp | WhatsApp Cloud API (Meta) | Planejado |
| E-mail | SMTP | Implementado (identidade); planejado (comunicação) |
| Pix | API Pix padrão do Banco Central (qualquer PSP aderente) | Planejado |
| IA do assistente | Amazon Bedrock (Converse API, modelos Claude) pela porta `LanguageModelPort` — SDK `software.amazon.awssdk:bedrockruntime` | Implementado; habilitação por ambiente após a homologação |
| SMS, boleto, telefonia/discador | Portas definidas; provedor a escolher | Aguardando definição de provedores |
