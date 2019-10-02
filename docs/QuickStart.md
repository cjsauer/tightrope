# Quick Start

Fullstack tutorial to guide you through your first tightrope application.

## Introduction

### What is tightrope?

Tightrope is a fullstack micro-framework for implementing web and mobile applications in Clojure(Script). While tightrope does come with its own opinions and models, its power stems from the composition of its constituent libraries. It is possible to use the framework while remaining ignorant of the details, which we will do in this tutorial, but know that there is great leverage underneath should you need it.

Tightrope's primary libraries include:

- [Rum][3] is a React(Native) wrapper that features mixins as a way to hook into a component’s lifecycle to extend its capabilities or change its behaviour. Tightrope exposes itself to the UI programmer through one of these mixins.
- [DataScript][4] is an immutable database and Datalog query engine for Clojure(Script). In tightrope, it is the central store for all client-side application state.
- [Pathom][5] is an engine for implementing a graph over your data, and is to Clojure what GraphQL is to JavaScript. In tightrope, it is perceived as the "connective tissue" between your client-side application state (DataScript) and the outside world.

You are encouraged to explore these libraries as far as your curiosity demands, but it is **not** required at this moment. In this tutorial, we will attempt to cover just enough of the basics for getting things done in tightrope through the course of building a real world example.

### What are we building?

### Getting set up

---

## Define a schema

---

## Write resolvers + mutations
  - Client
  - Server
  - Shared

---

## Create server handler

---

## Connect client to API

---

## Fetching data with `q` and `freshen`

---

## Mutating external state with `mutate`

---

## Mutating local state with `upsert`

[1]: https://www.apollographql.com
[2]: http://fulcro.fulcrologic.com
[3]: https://github.com/tonsky/rum
[4]: https://github.com/tonsky/datascript
[5]: https://github.com/wilkerlucio/pathom
