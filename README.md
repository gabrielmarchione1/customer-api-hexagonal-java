# Customer API — Arquitetura Hexagonal com Java

API REST para cadastro e manutenção de clientes, construída como projeto de estudo de **Arquitetura Hexagonal (Ports and Adapters)**. O núcleo da aplicação concentra os casos de uso e o domínio; adaptadores conectam esse núcleo ao MongoDB, a um serviço HTTP de endereços e ao Kafka.

> Este README descreve o código que está neste repositório. O serviço externo que valida CPF não faz parte do projeto: o fluxo de retorno é demonstrado publicando uma mensagem no tópico de resposta.

## O que a aplicação faz

- Cria, consulta, altera e exclui clientes.
- Busca o endereço do cliente por CEP em um serviço HTTP.
- Persiste clientes no MongoDB.
- Publica o CPF para validação assíncrona no Kafka e consome o resultado para atualizar o cliente.
- Usa portas e adaptadores para manter as regras dos casos de uso desacopladas de Spring, MongoDB, HTTP e Kafka.
- Verifica regras de organização e dependência entre camadas com ArchUnit.

## Tecnologias

- Java 17 e Gradle Wrapper 9.8.2.
- Spring Boot 4.1.1, Spring Web MVC, Validation, Data MongoDB e Kafka.
- Spring Cloud OpenFeign 2025.1.2 para a chamada ao serviço de endereços.
- MapStruct 1.5.2.Final e Lombok.
- MongoDB e Kafka; Docker Compose também sobe Zookeeper, Kafdrop e Mongo Express.
- ArchUnit 1.2.1 para testes de arquitetura.

As versões estão declaradas em `hexagonal/build.gradle` e `hexagonal/gradle/wrapper/gradle-wrapper.properties`.

## Arquitetura

O código Java está em `com.marchione.hexagonal`. A camada `application` contém o domínio, os casos de uso e as portas. Adaptadores de entrada recebem requisições HTTP e mensagens Kafka; adaptadores de saída integram com o serviço de endereços, MongoDB e Kafka. A montagem dos casos de uso é feita em `config`.

```text
HTTP ──> CustomerController ──> portas de entrada ──> casos de uso
                                                       │
Kafka ─> ReceiveValidatedCpfConsumer ──────────────────┤
                                                       v
                         portas de saída <── adaptadores de saída
                           │             │              │
                         Feign         MongoDB         Kafka
```

### Pacotes

| Pacote | Papel |
|---|---|
| `application.core.domain` | Modelos de domínio `Customer` e `Address`, sem anotações de framework. |
| `application.core.usecase` | Casos de uso de inserção, busca, alteração e exclusão. |
| `application.ports.in` | Interfaces de entrada implementadas pelos casos de uso. |
| `application.ports.out` | Interfaces usadas pelo núcleo para acessar serviços externos. |
| `adapters.in.controller` | API REST, DTOs e mapeador da API. |
| `adapters.in.consumer` | Consumidor de resultado da validação de CPF e modelo da mensagem. |
| `adapters.out` | Implementações das portas de saída. |
| `adapters.out.client` | Cliente Feign e modelos do serviço de endereço. |
| `adapters.out.repository` | Repositório MongoDB, entidades e mapeador. |
| `config` | Beans dos casos de uso e configuração do Kafka. |

Os casos de uso não recebem anotações do Spring. As classes `InsertCustomerConfig`, `FindCustomerByIdConfig`, `UpdateCustomerConfig` e `DeleteCustomerByIdConfig` criam os respectivos beans, conectando portas e adaptadores.

## Fluxos principais

### Cadastro

1. `CustomerController` valida e converte o corpo da requisição.
2. `InsertCustomerUseCase` busca o endereço pelo CEP usando a porta de saída.
3. O cliente é gravado no MongoDB.
4. O CPF é enviado como texto ao tópico `tp-cpf-validation`.

O domínio inicializa `validCpf` como `false`. A gravação e o envio Kafka são operações sequenciais e não atômicas: se o envio falhar depois da gravação, o cliente permanece cadastrado.

### Retorno da validação

`ReceiveValidatedCpfConsumer` escuta `tp-cpf-validated`, converte o JSON de `CustomerMessage` para o domínio e chama `UpdateCustomerUseCase`. Esse caso de uso é compartilhado com o endpoint `PUT`. A mensagem do consumidor contém `id`, `name`, `zipCode`, `cpf` e `validCpf`.

### Alteração e exclusão

Antes de alterar ou excluir, os casos de uso consultam o cliente pela porta de entrada da busca por ID. A alteração também resolve novamente o endereço pelo CEP informado.

## API REST

Endereço padrão: `http://localhost:8081/api/v1/customers`.

| Ação | Método e caminho | Corpo | Resposta implementada |
|---|---|---|---|
| Cadastrar | `POST /api/v1/customers` | `name`, `cpf`, `zipCode` | `200 OK`, sem corpo |
| Consultar | `GET /api/v1/customers/{id}` | — | `200 OK`, cliente |
| Alterar | `PUT /api/v1/customers/{id}` | `name`, `cpf`, `zipCode` | `204 No Content` |
| Excluir | `DELETE /api/v1/customers/{id}` | — | `204 No Content` |

Os campos do corpo são obrigatórios e usam `@NotBlank`. O endereço é obtido a partir do CEP; não é aceito no corpo da API. A resposta de consulta contém `name`, `address`, `cpf` e `validCpf`, mas **não contém `id`**.

Exemplo de cadastro:

```bash
curl -i -X POST http://localhost:8081/api/v1/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Maria","cpf":"12345678900","zipCode":"38400000"}'
```

Consulta e exemplo de resposta:

```bash
curl -i http://localhost:8081/api/v1/customers/<id>
```

```json
{
  "name": "Maria",
  "address": {
    "street": "Rua Hexagonal",
    "city": "Uberlândia",
    "state": "Minas Gerais"
  },
  "cpf": "12345678900",
  "validCpf": false
}
```

Exemplo de alteração:

```bash
curl -i -X PUT http://localhost:8081/api/v1/customers/<id> \
  -H 'Content-Type: application/json' \
  -d '{"name":"Maria","cpf":"12345678900","zipCode":"38400001"}'
```

Exemplo de exclusão:

```bash
curl -i -X DELETE http://localhost:8081/api/v1/customers/<id>
```

Corpos inválidos resultam em `400 Bad Request` pela validação. Cliente inexistente provoca uma `RuntimeException` em `FindCustomerByIdUseCase`; não há tratamento específico para convertê-la em `404`, então a aplicação atualmente responde com erro interno (`500`).

## Kafka

A configuração atual está codificada em `KafkaProducerConfig`, `KafkaConsumerConfig` e `SendCpfValidationAdapter`:

| Tópico | Direção | Conteúdo |
|---|---|---|
| `tp-cpf-validation` | Produção | CPF como `String`. |
| `tp-cpf-validated` | Consumo | JSON convertido para `CustomerMessage`, com `id`, `name`, `zipCode`, `cpf` e `validCpf`. |

O broker configurado no código é `localhost:9092`; o grupo consumidor é `marchione`. O consumidor usa `earliest` quando ainda não existe offset para o grupo. O projeto não contém o serviço que valida CPF nem publica automaticamente a resposta.

Para simular uma resposta, publique em `tp-cpf-validated` um JSON semelhante ao seguinte, substituindo `<id>` por um ID existente:

```json
{
  "id": "<id>",
  "name": "Maria",
  "zipCode": "38400000",
  "cpf": "12345678900",
  "validCpf": true
}
```

## Como executar localmente

### Pré-requisitos

- JDK 17.
- Docker com Docker Compose.
- WireMock standalone para simular o serviço HTTP de endereços.

### 1. Subir as dependências

```bash
cd hexagonal/docker-local
docker compose up -d
```

O `docker-compose.yml` sobe:

| Serviço | Porta local | Uso |
|---|---:|---|
| Zookeeper | `2181` | Coordenação do Kafka. |
| Kafka | `9092` | Broker usado pela aplicação. |
| Kafdrop | `9000` | Interface para tópicos e mensagens. |
| MongoDB | `27017` | Banco da aplicação. |
| Mongo Express | `8083` | Interface web para MongoDB. |

No ambiente local, o MongoDB é iniciado com usuário `root`, senha `example` e banco configurado como `hexagonal` na aplicação. São credenciais de desenvolvimento, não use esses valores em ambientes compartilhados ou de produção.

### 2. Criar os tópicos

Execute a partir de `hexagonal/docker-local`:

```bash
docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic tp-cpf-validation
docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic tp-cpf-validated
```

Também é possível criar os tópicos pelo Kafdrop em `http://localhost:9000`.

### 3. Simular o serviço de endereços

A URL configurada na aplicação é `http://localhost:8082/addresses`. Inicie o WireMock na porta 8082 com um mapeamento para cada CEP usado nos testes. Por exemplo, salve este conteúdo em `mappings/address.json` na pasta de trabalho do WireMock:

```json
{
  "request": {
    "method": "GET",
    "url": "/addresses/38400000"
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "street": "Rua Hexagonal",
      "city": "Uberlândia",
      "state": "Minas Gerais"
    }
  }
}
```

Inicie-o apontando para essa pasta de mappings:

```bash
java -jar wiremock-standalone-<versao>.jar --port 8082 --root-dir <pasta-do-wiremock>
```

O README original do projeto também documentava um segundo CEP de exemplo, `38400001`, associado a Rua das Flores, São Paulo. Esse mapeamento deve ser adicionado ao WireMock se for usado no `PUT`. CEPs sem mapeamento não são resolvidos pelo mock.

### 4. Iniciar a aplicação

A configuração padrão está em `hexagonal/src/main/resources/application.yml`: servidor na porta `8081`, serviço de endereço na porta `8082` e MongoDB em `localhost:27017`.

```bash
cd hexagonal
./gradlew bootRun
```

No Windows, use `gradlew.bat bootRun`. Após a inicialização, a API fica disponível em `http://localhost:8081`.

## Testes

Os testes existentes são testes de arquitetura com ArchUnit; não há atualmente testes unitários de casos de uso nem testes de integração implementados no projeto.

```bash
cd hexagonal
./gradlew test
```

- `LayeredArchitectureTest` verifica regras de dependência entre adaptadores, casos de uso, portas e configuração.
- `NamingConventionTest` verifica convenções de sufixos e pacotes para componentes como `Controller`, `Adapter`, `UseCase`, `InputPort` e `OutputPort`.

## Estrutura do repositório

```text
.
├── README.md
└── hexagonal/                         Aplicação Gradle
    ├── build.gradle                   Plugins e dependências
    ├── docker-local/docker-compose.yml Serviços locais
    ├── gradle/wrapper/                Wrapper Gradle
    └── src/
        ├── main/java/com/marchione/hexagonal/
        │   ├── adapters/              Entradas HTTP/Kafka e saídas HTTP/Mongo/Kafka
        │   ├── application/           Domínio, casos de uso e portas
        │   └── config/                Beans e configuração do Kafka
        ├── main/resources/application.yml
        └── test/java/com/marchione/hexagonal/architecture/
            ├── LayeredArchitectureTest.java
            └── NamingConventionTest.java
```

## Limitações e próximos passos

- Implementar tratamento explícito de cliente inexistente e resposta HTTP `404`.
- Avaliar retornar `201 Created` no cadastro e expor o ID do cliente criado.
- Definir se uma alteração deve preservar o `validCpf` previamente validado; o mapeamento da requisição não o recebe.
- Tornar gravação no MongoDB e publicação Kafka resilientes a falhas, por exemplo, com o padrão outbox.
- Configurar retry e dead-letter topic para falhas no consumidor.
- Externalizar broker, tópicos, grupo Kafka e credenciais para configuração apropriada por ambiente.
- Adicionar testes unitários e de integração, além dos testes de arquitetura existentes.
- Documentar os endpoints com OpenAPI/Swagger.

## Créditos

Projeto de estudo inspirado no curso [Arquitetura Hexagonal ou Ports and Adapters na Prática](https://www.udemy.com/course/arquitetura-hexagonal-ou-ports-and-adapters-na-pratica/).

Autor: [gabrielmarchione1](https://github.com/gabrielmarchione1).
