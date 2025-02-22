Navigator Backend
=================

The Navigator backend is a Scala application providing

- a web server that exposes a GraphQL API with some predefined endpoints,
  such as returning all visible contracts.
- a platform client that reacts to events happening in the platform
- a simple sign-in API

Usage
-----

The Navigator backend is written in Scala, making heavy use of
[Akka](http://akka.io/) and [Sangria](http://sangria-graphql.org/) (for
GraphQL). It uses the [Scala Build Tool](http://www.scala-sbt.org/).

We can build and run a basic the backend using the following commands:

```bash
# Build a distribution archive ("fat jar"):
bazel build //navigator/backend:navigator-binary_distribute.jar

# Run without arguments to show usage:
java -jar bazel-bin/navigator/backend/navigator-binary_distribute.jar --help

# Create a dummy configuration file
cat << EOF > navigator.conf
users {
  OPERATOR: {
    party=OPERATOR
  }
}
EOF

# Start the web server
java -jar bazel-bin/navigator/backend/navigator-binary_distribute.jar server -c navigator.conf
```

If you start the server and the configuration file doesn't exist, the server will
write a configuration template and quit. You can then edit the configuration file
and start again the server.

Custom UI backends
------------------

## Extend the `UIBackend` base class

The base class for UI backends is the
[`UIBackend`](src/main/scala/com/digitalasset/navigator/backend/UIBackend.scala),
a Scala class that implements a ledger indexer and a web-server which exposes a
number of default GraphQL endpoints and a simple Session API. It also allows has
hooks for adding custom GraphQL endpoints.

To create a basic UI backend, you can extend `UIBackend` without any custom
additions:

```scala
object DefaultUIBackend extends UIBackend {
  override def customEndpoints: Set[CustomEndpoint[_]] = Set()
  override val defaultConfigFile: Path = Paths.get("my-app.conf")
  override def applicationInfo: ApplicationInfo = ApplicationInfo(...)
  override def banner: Option[String] = Some(...)
}
```

You can then run your app with this as the main class.

## Custom endpoints

A custom endpoint is an extra endpoint for a UI backend that binds a name to
some data that can be represented in GraphQL and a function to calculate that
data. For example:

```scala
object ExampleUIBackend extends UIBackend {

  override def customEndpoints: Set[CustomEndpoint[_]] = Set(contractsIdEndpoint)

  final case class TemplateId(id: String)
  final case class ContractProjection(id: String, template: TemplateId, argument: RecordArgument)

  /** Endpoing for the contract ids */
  private val contractsIdEndpoint = new CustomEndpoint[ContractProjection] {
    /** The endpoint to be used as GraphQL top-level for the data served by this */
    override def endpointName: String = "contract_id"

    /** For each contract in the ledger, extract contract id and template id */
    override def calculate(ledger: Ledger): Seq[ContractProjection] =
      ledger.allContracts().map(contract =>
        ContractProjection(contract.idString, TemplateId(contract.template.idString), contract.argument))
  }
}
```

This adds an endpoint called `contract_id` that returns a list of contract
projections that include the contract ID, the template ID, and the contract
arguments. You would then pass this custom endpoint to a UI backend subclass:

There are two components of a `CustomEndpoint`:

- The `endpointName` is the name used to bind this custom endpoint to the root
  of the GraphQL Schema. Note that each endpoint must have a unique
  `endpointName` and that default `endpointName`s -- `contracts` or `templates`
  for example -- cannot be used in custom endpoints either.

- The `calculate` function is used to extract the data to serve from the
  [`Ledger`](src/main/scala/com/digitalasset/ui/backend/model/Ledger.scala).
  This function must create a sequence of instances of a scala *case class*.

The endpoint `contract_id` becomes available in the root of the graphql schema:

```graphql
type ContractProjection {
  id: String!
  template: TemplateId!
  argument: Value!
}

type TemplateId {
  id: String!
}

type Query {
  contract_id: [ContractProjection!]!
}
```

The schema is automatically generated by the UI backend by following the
structure of a "row" case class. For instance `TemplateId` is a nested structure
inside `ContractProjection` in the Scala code and for this reason it will be a
nested structure in the GraphQL Schema too.

## Automatic generation of GraphQL Schemas for custom endpoints

The code for the GraphQL endpoint is automatically generated at compile time by
the UI backend. This means that there are some limitations in what can be
exposed as result of the `calculate` function. Specifically:

- The name of the "row" model case class must be a unique name. Don't use
  `Contract` or `Template` or any other name already used in the [`GraphQL
  Schema`](src/main/scala/com/digitalasset/ui/backend/graphql/GraphQLSchema.scala).
  For example, you can't rename `ContractProjection` to `Contract`.

- The main "row" class must be modelled as a Scala case class and it must
  contain fields that are either a case class or Scala primitives. If something
  is not supported, you won't be able to compile your code.

If some type is not supported by the generator, it is possible to add support
for it by providing either an instance of the typeclass
[`GraphQLLeaf`](src/main/scala/com/digitalasset/ui/backend/graphqless/GraphQLLeaf.scala)
for simple types, e.g. `String` or `Int`, or an instance of the typeclass
[`GraphQLObject`](src/main/scala/com/digitalasset/ui/backend/graphqless/GraphQLObject.scala)
for complex types which have fields.

Common tasks
------------

## How do I query the Backend without a frontend?

The UI backend exposes a [GraphiQL](https://github.com/graphql/graphiql) page to
let the developer run queries interactively. You can find it at the address
`<address_of_the_navigator_backend>/graphql`. Note the "Docs" link in the upper
right corner and also that you have to be logged in order to run queries. You
can either use the frontend in the same browser to login or you can login
manually via command line as follows:

- Send a JSON POST request with the `userId` and make note of
  the cookie:

  ```bash
  > curl -H "Content-Type: application/json" -d'{ "userId":"PARTY" }' localhost:4000/api/session/ -i
  HTTP/1.1 200 OK
  Set-Cookie: session-id=8b4601d4-7113-407b-9b81-7fd5b213a96b; Path=/
  Server: akka-http/10.0.4
  Date: Tue, 13 Jun 2017 16:47:38 GMT
  Content-Type: application/json
  Content-Length: 92

  {"type":"session","user":{"id":"BANK1","role":"bank","party":"BANK1","canAdvanceTime":true}}
  ```

- Open the GraphiQL page in a browser (`localhost:4000/graphql` for example).

- Write `javascript:document.cookie="session-id=<the_id_recceived>"` in the location bar.

- You can now use the GraphiQL to query your data.

We plan to add an option to run the server in unauthenticated mode so that these steps become unnecessary.

Session API
-----------

In addition to the GraphQL endpoint exposing data, the user needs to act as
some party. This is to know which party's "view" on the ledger to expose. The
backend supplies the frontend with a list of available users.
The chosen user is set in a cookie and therefore
persists across reloads.

```typescript
type UserId = string;
type Party = string;
type Role = string;
type User = {
  id: UserId;
  party: Party;
  canAdvanceTime: boolean;
  role?: Role;
};

type Status = Session | SignIn;
type Session = { type: 'session'; user: User; }
type SignIn = {
  type: 'sign-in';
  method: SignInMethod;
  error?: 'invalid-credentials';
}

type SignInMethod = SignInSelect
type SignInSelect = { type: 'select', users: UserId[] }
```

```bash
# Get current session or sign in
GET /session/ => Status

# Sign in
POST /session/ -d'{ userId: UserId }' => Status

# Sign out
DELETE /session/ => SignIn
```

Architecture notes
------------------

This section contains short notes for anyone that wishes to make changes to the UI backend library.

- The backend defines its own internal representation of ledger objects
- The backend communicates with the ledger via a JSON-based "ledger API"
- The backend communicates with the frontend via a JSON-based "frontend API"
- These are the relevant source files:
  - `model/Model`: Defines the internal representations of ledger objects
  - `model/Util`: (SDaml package) -> (internal model)
  - `graphql/JsonType`: (internal model) <-> (frontend API JSON format)
  - `store/platform/PlatformSubscriber`: (ledger API JSON format) <-> (internal model)
