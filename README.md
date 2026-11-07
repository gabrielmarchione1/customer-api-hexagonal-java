# customer-api-hexagonal-java

API de clientes com **Arquitetura Hexagonal (Ports and Adapters)**, **Java**, **Spring Boot**, **Kafka** e **MongoDB**.

O projeto implementa um CRUD de clientes em que a **regra de negócio fica isolada** (sem frameworks, sem conhecer banco de dados ou mensageria) e toda a comunicação com o mundo externo passa por **portas** e **adaptadores**. Ao cadastrar um cliente, a API busca o endereço em outro microserviço pelo CEP, salva no MongoDB e envia o CPF para uma fila do Kafka para validação. O resultado da validação volta por outro tópico e atualiza o cadastro.

Projeto desenvolvido durante o curso [Arquitetura Hexagonal ou Ports and Adapters na Prática](https://www.udemy.com/course/arquitetura-hexagonal-ou-ports-and-adapters-na-pratica/), com testes de arquitetura em ArchUnit.

---

## Sumário

- [Funcionalidades](#funcionalidades)
- [Tecnologias](#tecnologias)
- [Arquitetura](#arquitetura)
- [Fluxo da aplicação](#fluxo-da-aplicação)
- [Endpoints da API](#endpoints-da-api)
- [Mensageria (Kafka)](#mensageria-kafka)
- [Como executar](#como-executar)
- [Testes de arquitetura](#testes-de-arquitetura)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Decisões de projeto](#decisões-de-projeto)
- [Limitações conhecidas e próximos passos](#limitações-conhecidas-e-próximos-passos)

---

## Funcionalidades

- Cadastrar um cliente (o endereço é buscado pelo CEP em outro microserviço).
- Consultar um cliente por ID.
- Alterar um cliente (nome, CPF e CEP).
- Excluir um cliente por ID.
- Enviar o CPF para validação assíncrona via Kafka.
- Receber o resultado da validação via Kafka e atualizar o cliente.
- Validar a arquitetura automaticamente com testes (ArchUnit).

## Tecnologias

| Tecnologia | Uso |
|---|---|
| Java | linguagem |
| Spring Boot | aplicação e injeção de dependência (apenas nos adaptadores e na config) |
| Spring Web | API REST |
| Spring Validation | validação do corpo das requisições (`@NotBlank`) |
| Spring Cloud OpenFeign | client HTTP para o microserviço de endereço |
| Spring Data MongoDB | persistência |
| Spring for Apache Kafka | produtor e consumidor de mensagens |
| MapStruct | conversão entre objetos (request, entidade, mensagem e domínio) |
| Lombok | redução de código repetitivo (somente nos adaptadores) |
| ArchUnit | testes de arquitetura |
| WireMock | simulação do microserviço de endereço |
| Docker Compose | Kafka, Zookeeper, MongoDB e interfaces de apoio |
| Gradle | build |

As versões exatas estão no `build.gradle`.

---

## Arquitetura

O projeto segue a **Arquitetura Hexagonal**: o núcleo da aplicação (`application`) não depende de nada externo. Os **adaptadores** implementam ou chamam as **portas**, e a camada `config` monta tudo.

```
                    ┌────────────────────────────────────────────┐
  Controller  ─────►│  Porta de entrada ─► Use Case ─► Porta de  │─────► Client (Feign) ──► Serviço de endereço
  (REST)            │                                  saída     │─────► Repository ──────► MongoDB
  Consumer    ─────►│          application (core)                │─────► Producer ────────► Kafka
  (Kafka)           └────────────────────────────────────────────┘
   adapters.in                                                        adapters.out
```

### Camadas e responsabilidades

| Camada | Pacote | Responsabilidade |
|---|---|---|
| Domínio | `application.core.domain` | `Customer` e `Address`, sem frameworks |
| Casos de uso | `application.core.usecase` | regras de negócio, uma classe por funcionalidade |
| Portas de entrada | `application.ports.in` | interfaces que expõem os casos de uso |
| Portas de saída | `application.ports.out` | interfaces que o core usa para falar com o exterior |
| Adaptadores de entrada | `adapters.in` | `controller` (REST) e `consumer` (Kafka) |
| Adaptadores de saída | `adapters.out` | `client` (microserviço), `repository` (MongoDB) e produtor do Kafka |
| Configuração | `config` | beans dos casos de uso e configurações do Kafka |

### Regras de dependência

| Camada | Quem pode acessar |
|---|---|
| Adaptadores de entrada | somente `config` |
| Adaptadores de saída | somente `config` |
| Casos de uso | somente `config` |
| Portas de entrada | casos de uso e adaptadores de entrada |
| Portas de saída | casos de uso e adaptadores de saída |
| `config` | ninguém |

Essas regras são verificadas por testes automatizados (veja [Testes de arquitetura](#testes-de-arquitetura)).

### Portas e adaptadores do projeto

**Portas de entrada**

| Porta | Implementada por |
|---|---|
| `InsertCustomerInputPort` | `InsertCustomerUseCase` |
| `FindCustomerByIdInputPort` | `FindCustomerByIdUseCase` |
| `UpdateCustomerInputPort` | `UpdateCustomerUseCase` |
| `DeleteCustomerByIdInputPort` | `DeleteCustomerByIdUseCase` |

**Portas de saída**

| Porta | Implementada por | Destino |
|---|---|---|
| `FindAddressByZipCodeOutputPort` | `FindAddressByZipCodeAdapter` | microserviço de endereço (Feign) |
| `InsertCustomerOutputPort` | `InsertCustomerAdapter` | MongoDB |
| `FindCustomerByIdOutputPort` | `FindCustomerByIdAdapter` | MongoDB |
| `UpdateCustomerOutputPort` | `UpdateCustomerAdapter` | MongoDB |
| `DeleteCustomerByIdOutputPort` | `DeleteCustomerByIdAdapter` | MongoDB |
| `SendCpfForValidationOutputPort` | `SendCpfValidationAdapter` | Kafka |

### Montagem dos beans

Os casos de uso **não têm anotações do Spring**. A camada `config` cria um bean para cada um, passando as implementações das portas:

| Configuração | Bean criado |
|---|---|
| `InsertCustomerConfig` | `InsertCustomerUseCase` |
| `FindCustomerByIdConfig` | `FindCustomerByIdUseCase` |
| `UpdateCustomerConfig` | `UpdateCustomerUseCase` |
| `DeleteCustomerByIdConfig` | `DeleteCustomerByIdUseCase` |

Quando um caso de uso recebe a **porta de entrada** de outro (como `FindCustomerByIdInputPort` no update e no delete), o bean passado é o próprio caso de uso que a implementa.

---

## Fluxo da aplicação

### Cadastro e validação do CPF

```
POST /api/v1/customers
   → CustomerController
      → CustomerMapper (CustomerRequest → Customer)
      → InsertCustomerInputPort
         → InsertCustomerUseCase
            1. busca o endereço pelo CEP (microserviço de endereço)
            2. salva o cliente no MongoDB (isValidCpf = false)
            3. envia o CPF para o tópico tp-cpf-validation
   ← 200 OK

API de validação de CPF (externa/simulada)
   → consome tp-cpf-validation, valida e publica em tp-cpf-validated

ReceiveValidatedCpfConsumer (tópico tp-cpf-validated)
   → CustomerMessageMapper (CustomerMessage → Customer)
   → UpdateCustomerInputPort
      → UpdateCustomerUseCase
         → atualiza o cliente (inclusive o isValidCpf)
```

### Alteração

```
PUT /api/v1/customers/{id}
   → UpdateCustomerUseCase
      1. verifica se o cliente existe (FindCustomerByIdInputPort)
      2. busca o endereço pelo CEP
      3. grava a alteração
   ← 204 No Content
```

### Exclusão

```
DELETE /api/v1/customers/{id}
   → DeleteCustomerByIdUseCase
      1. verifica se o cliente existe (FindCustomerByIdInputPort)
      2. remove o cliente
   ← 204 No Content
```

---

## Endpoints da API

URL base: `http://localhost:8081/api/v1/customers`

| Operação | Método | Endpoint | Corpo | Resposta |
|---|---|---|---|---|
| Cadastrar | `POST` | `/api/v1/customers` | `CustomerRequest` | `200 OK`, sem corpo |
| Buscar por ID | `GET` | `/api/v1/customers/{id}` | | `200 OK`, `CustomerResponse` |
| Alterar | `PUT` | `/api/v1/customers/{id}` | `CustomerRequest` | `204 No Content` |
| Excluir | `DELETE` | `/api/v1/customers/{id}` | | `204 No Content` |

### Corpo da requisição (`CustomerRequest`)

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `name` | string | sim | nome do cliente |
| `cpf` | string | sim | CPF do cliente |
| `zipCode` | string | sim | CEP, usado para buscar o endereço |

Os três campos usam `@NotBlank`: não podem ser nulos, vazios nem conter só espaços. O `id` é gerado pelo MongoDB, e o endereço é obtido pelo CEP.

### Exemplos

**Cadastrar**

```bash
curl -X POST http://localhost:8081/api/v1/customers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Maria",
    "cpf": "12345678900",
    "zipCode": "38400000"
  }'
```

**Buscar por ID**

```bash
curl http://localhost:8081/api/v1/customers/{id}
```

Resposta:

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

> O `id` não é devolvido na resposta. Para obter o `id`, consulte o documento no MongoDB.

**Alterar**

```bash
curl -X PUT http://localhost:8081/api/v1/customers/{id} \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Sebastião",
    "cpf": "12345678900",
    "zipCode": "38400001"
  }'
```

**Excluir**

```bash
curl -X DELETE http://localhost:8081/api/v1/customers/{id}
```

### Códigos de resposta

| Código | Quando |
|---|---|
| `200 OK` | cadastro e busca realizados |
| `204 No Content` | alteração e exclusão realizadas |
| `400 Bad Request` | corpo inválido (campo em branco, por exemplo) |
| `500 Internal Server Error` | cliente não encontrado (ver [limitações](#limitações-conhecidas-e-próximos-passos)) |

---

## Mensageria (Kafka)

| Tópico | Direção | Descrição |
|---|---|---|
| `tp-cpf-validation` | a aplicação **envia** | CPF do cliente cadastrado, enviado como `String` |
| `tp-cpf-validated` | a aplicação **recebe** | dados do cliente com o resultado da validação do CPF |

- **Broker:** `localhost:9092`
- **Group id:** `marchione`
- **Produtor:** serializa chave e valor como `String`.
- **Consumidor:** chave como `String` e valor como **JSON**, convertido para `CustomerMessage`. Lê desde o início do tópico quando não há offset salvo (`earliest`).

### Mensagem recebida em `tp-cpf-validated`

```json
{
  "id": "<id do cliente>",
  "name": "Maria",
  "zipCode": "38400000",
  "cpf": "12345678900",
  "isValidCpf": true
}
```

Ao receber a mensagem, a aplicação atualiza o cliente com o `UpdateCustomerUseCase`, o mesmo usado pelo `PUT`.

---

## Como executar

### Pré-requisitos

- **Java**, na versão definida no `build.gradle`
- **Docker** e **Docker Compose**
- **WireMock** (JAR standalone), para simular o serviço de endereço: <https://wiremock.org/docs/download-and-installation/>

### 1. Subir Kafka e MongoDB

Na pasta `docker-local`:

```bash
docker compose up
```

| Serviço | Porta | Observação |
|---|---|---|
| Zookeeper | `2181` | |
| Kafka | `9092` | endereço usado pela aplicação (`localhost:9092`) |
| Kafdrop | `9000` | interface web para ver tópicos e mensagens |
| MongoDB | `27017` | usuário `root`, senha `example` |
| Mongo Express | `8083` | interface web do Mongo |

### 2. Criar os tópicos do Kafka

```bash
docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --topic tp-cpf-validation
docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --topic tp-cpf-validated
```

Também é possível criá-los pelo Kafdrop (`http://localhost:9000`) ou por um plugin de Kafka da IDE.

### 3. Subir o WireMock na porta 8082

Em uma pasta de trabalho, crie a pasta `mappings` com os dois arquivos abaixo e execute:

```bash
java -jar wiremock-standalone-<versão>.jar --port 8082
```

> Sempre que criar ou alterar um arquivo em `mappings`, pare e inicie o WireMock novamente.

`mappings/address.json`

```json
{
    "request": {
        "method": "GET",
        "url": "/addresses/38400000"
    },
    "response": {
        "status": 200,
        "headers": {
            "Content-Type": "application/json"
        },
        "jsonBody": {
            "street": "Rua Hexagonal",
            "city": "Uberlândia",
            "state": "Minas Gerais"
        }
    }
}
```

`mappings/address_2.json`

```json
{
    "request": {
        "method": "GET",
        "url": "/addresses/38400001"
    },
    "response": {
        "status": 200,
        "headers": {
            "Content-Type": "application/json"
        },
        "jsonBody": {
            "street": "Rua das Flores",
            "city": "São Paulo",
            "state": "São Paulo"
        }
    }
}
```

| CEP | Endereço devolvido |
|---|---|
| `38400000` | Rua Hexagonal, Uberlândia, Minas Gerais |
| `38400001` | Rua das Flores, São Paulo, São Paulo |

Qualquer outro CEP devolve `404` no WireMock, e o cadastro falha. Use um dos dois acima.

### 4. Subir a aplicação

Configuração em `application.yml`:

```yaml
server:
    port: 8081

marchione:
  client:
    address:
      url: http://localhost:8082/addresses

spring:
  mongodb:
    host: localhost
    port: 27017
    authentication-database: admin
    username: root
    password: example
    database: hexagonal
```

> As propriedades do MongoDB ficam em `spring.mongodb` ou `spring.data.mongodb`, conforme a versão do Spring Boot do projeto.

Na pasta do projeto (`hexagonal`):

```bash
./gradlew bootRun
```

A API fica disponível em `http://localhost:8081`.

### 5. Testar o fluxo completo

1. `POST /api/v1/customers` com o CEP `38400000`.
2. Conferir no MongoDB:

   ```
   use hexagonal
   db.customers.find()
   ```

   O cliente é gravado com o endereço de Uberlândia e `isValidCpf` igual a `false`.
3. `GET /api/v1/customers/{id}` com o `id` do documento.
4. Conferir no Kafdrop (ou no Offset Explorer) que chegou uma mensagem em `tp-cpf-validation`.
5. `PUT /api/v1/customers/{id}` mudando o nome e o CEP para `38400001`.
6. Publicar manualmente uma mensagem em `tp-cpf-validated` (JSON acima) para simular a validação do CPF e conferir que `isValidCpf` passa a `true`.
7. `DELETE /api/v1/customers/{id}`.

---

## Testes de arquitetura

Os testes usam **ArchUnit** e ficam em `src/test/java/com/marchione/hexagonal/architecture`.

### `LayeredArchitectureTest`: acesso entre camadas

Define as camadas `AdaptersIn`, `AdaptersOut`, `UseCase`, `PortsIn`, `PortsOut` e `Config` e garante as regras da tabela de [dependências](#regras-de-dependência). O domínio fica de fora de propósito: qualquer camada pode usá-lo.

### `NamingConventionTest`: nomes e pacotes

Dois grupos de regras, com 14 testes cada:

1. **Nome → pacote:** classes com determinado sufixo só podem estar no pacote correspondente.
2. **Pacote → nome:** classes de um pacote só podem ter o sufixo esperado.

| Sufixo | Pacote |
|---|---|
| `Consumer` | `adapters.in.consumer` |
| `Mapper` / `MapperImpl` | `mapper` de `adapters.in.consumer`, `adapters.in.controller`, `adapters.out.client` e `adapters.out.repository` |
| `Message` | `adapters.in.consumer.message` |
| `Controller` | `adapters.in.controller` |
| `Request` | `request` (controller ou client) |
| `Response` | `response` (controller ou client) |
| `Adapter` | `adapters.out` |
| `Client` | `adapters.out.client` |
| `Repository` | `adapters.out.repository` |
| `Entity` | `adapters.out.repository.entity` |
| `UseCase` | `application.core.usecase` |
| `InputPort` | `application.ports.in` |
| `OutputPort` | `application.ports.out` |
| `Config` | `config` |

O `MapperImpl` é aceito porque o MapStruct gera uma implementação para cada mapper.

Para rodar:

```bash
./gradlew test
```

---

## Estrutura do projeto

```
hexagonal
├── docker-local
│   └── docker-compose.yml
└── src
    ├── main
    │   ├── java/com/marchione/hexagonal
    │   │   ├── HexagonalApplication.java
    │   │   ├── adapters
    │   │   │   ├── in
    │   │   │   │   ├── consumer
    │   │   │   │   │   ├── ReceiveValidatedCpfConsumer.java
    │   │   │   │   │   ├── mapper/CustomerMessageMapper.java
    │   │   │   │   │   └── message/CustomerMessage.java
    │   │   │   │   └── controller
    │   │   │   │       ├── CustomerController.java
    │   │   │   │       ├── mapper/CustomerMapper.java
    │   │   │   │       ├── request/CustomerRequest.java
    │   │   │   │       └── response/{CustomerResponse, AddressResponse}.java
    │   │   │   └── out
    │   │   │       ├── FindAddressByZipCodeAdapter.java
    │   │   │       ├── InsertCustomerAdapter.java
    │   │   │       ├── FindCustomerByIdAdapter.java
    │   │   │       ├── UpdateCustomerAdapter.java
    │   │   │       ├── DeleteCustomerByIdAdapter.java
    │   │   │       ├── SendCpfValidationAdapter.java
    │   │   │       ├── client
    │   │   │       │   ├── FindAddressByZipCodeClient.java
    │   │   │       │   ├── mapper/AddressResponseMapper.java
    │   │   │       │   └── response/AddressResponse.java
    │   │   │       └── repository
    │   │   │           ├── CustomerRepository.java
    │   │   │           ├── entity/{CustomerEntity, AddressEntity}.java
    │   │   │           └── mapper/CustomerEntityMapper.java
    │   │   ├── application
    │   │   │   ├── core
    │   │   │   │   ├── domain/{Customer, Address}.java
    │   │   │   │   └── usecase/{Insert, FindById, Update, DeleteById}...UseCase.java
    │   │   │   └── ports
    │   │   │       ├── in/{Insert, FindById, Update, DeleteById}...InputPort.java
    │   │   │       └── out/...OutputPort.java
    │   │   └── config
    │   │       ├── {Insert, FindById, Update, DeleteById}...Config.java
    │   │       ├── KafkaProducerConfig.java
    │   │       └── KafkaConsumerConfig.java
    │   └── resources/application.yml
    └── test/java/com/marchione/hexagonal/architecture
        ├── LayeredArchitectureTest.java
        └── NamingConventionTest.java
```

---

## Decisões de projeto

- **Core sem framework:** as classes de `application` não usam Spring, Lombok nem MapStruct. Getters, setters e construtores do domínio são escritos manualmente.
- **Um modelo por borda:** o controller usa `CustomerRequest` e `CustomerResponse`, o MongoDB usa `CustomerEntity`, o Kafka usa `CustomerMessage`, e o microserviço de endereço usa `AddressResponse`. O domínio não vaza para fora, e as conversões são feitas por mappers (MapStruct).
- **Regra de negócio no use case:** a decisão "cliente não encontrado" fica no `FindCustomerByIdUseCase`. Os adapters só devolvem um `Optional`.
- **Reaproveitamento por portas de entrada:** `update` e `delete` verificam a existência do cliente chamando a porta de entrada da busca por ID, sem duplicar regra.
- **Várias entradas, mesma regra:** o `PUT` e o consumidor do Kafka usam o mesmo `UpdateCustomerUseCase`.
- **Montagem na `config`:** é o único ponto que conhece os adapters e os use cases ao mesmo tempo.
- **Arquitetura protegida por testes:** regras de dependência e de nomenclatura são verificadas a cada build.

### Vantagens e desvantagens

| Vantagens | Desvantagens |
|---|---|
| core isolado e fácil de testar | mais código e mais abstrações que em MVC |
| troca de tecnologia só nos adaptadores | curva de aprendizado maior |
| estrutura que "se explica" ao ser lida | esforço de mapeamento entre modelos |
| uma classe, uma responsabilidade (SOLID) | pode ser exagero em CRUDs simples |

---

## Limitações conhecidas e próximos passos

- **Cliente não encontrado:** o `FindCustomerByIdUseCase` lança uma `RuntimeException` genérica, que a API devolve como `500`. Próximo passo: criar uma exceção própria (como `CustomerNotFoundException`) e tratá-la com `@RestControllerAdvice`, devolvendo `404`.
- **Cadastro devolve `200`:** pela convenção REST, criação costuma retornar `201 Created` com o `id` ou o `Location` do recurso.
- **Alteração e `isValidCpf`:** o `PUT` grava o cliente inteiro, e o `isValidCpf` vindo da requisição não é informado (nasce `false`). Vale definir se a alteração deve preservar o valor já validado.
- **Envio ao Kafka não atômico:** o cliente é salvo antes de o CPF ser enviado. Se o envio falhar, o cadastro fica com o CPF não validado. Uma alternativa é o padrão *outbox*.
- **Tratamento de erros do consumidor:** falhas ao processar mensagens (cliente inexistente, por exemplo) ainda não têm política de retry nem *dead letter topic*.
- **Configurações fixas no código:** endereço do Kafka e nomes dos tópicos poderiam ir para o `application.yml`.
- **Credenciais:** `root` / `example` são os padrões do ambiente de estudo. Em outros ambientes, use variáveis de ambiente ou um arquivo local fora do versionamento.
- **Testes de unidade:** os casos de uso recebem só interfaces, então são fáceis de testar com portas simuladas. Ainda não há testes unitários ou de integração, além dos de arquitetura.
- **Documentação da API:** adicionar OpenAPI/Swagger.

---

## Autor

[gabrielmarchione1](https://github.com/gabrielmarchione1)
