# Quick Start

Fullstack tutorial to guide you through your first tightrope application.

## Introduction

### What is tightrope?

Tightrope is a fullstack micro-framework for implementing web and mobile applications in Clojure(Script). While tightrope does come with its own opinions and models, its power stems from the composition of its constituent libraries. It is possible to use the framework while remaining ignorant of the details, which we will do in this tutorial, but know that there is great leverage underneath should you need it.

Tightrope's primary libraries include:

- [Rum][rum] is a React(Native) wrapper that features mixins as a way to hook into a component’s lifecycle to extend its capabilities or change its behaviour. Tightrope exposes itself to the UI programmer through one of these mixins.
- [DataScript][ds] is an immutable database and Datalog query engine for Clojure(Script). In tightrope, it is the central store for all client-side application state.
- [Pathom][pathom] is an engine for implementing a graph over your data, and is to Clojure what GraphQL is to JavaScript. In tightrope, it is perceived as the "connective tissue" between your client-side application state (DataScript) and the outside world.

You are encouraged to explore these libraries as far as your curiosity demands, but it is **not** required at this moment. In this tutorial, we will attempt to cover just enough of the basics for getting things done in tightrope through the course of building a real world example.

### What are we building?

We'll take inspiration from (i.e. steal) the example project described in the [Apollo tutorial][apollotut]. To quote their introduction:

> In this tutorial, we'll build an interactive app for reserving your spot on an upcoming Space-X launch. You can think of it as an Airbnb for space travel! All of the data is real, thanks to the [SpaceX-API][spacex].
> The app has five screens: a login screen, a list of launches, a launch detail, a profile page, and a cart.

Our goal will be similar in intent, that is to cover real problems like authentication, pagination, and state management.

### Prequisites

This tutorial assumes that you're familiar with Clojure and ClojureScript, have fetched data from APIs, and have a basic familiarity with React. Thankfully, you'll find that Rum is regularly more concise, and simpler than React in many ways.

#### System requirements

Make sure the following are installed on your system:

- [Java][openjdk] v8 or greater
- [Clojure][clj] v1.10 or greater
- [Node][node] v8.x or greater
- [git][git] v2.14.1 or greater

and your preferred Node package manager (if you have no preference, `yarn` is my favorite):

- [yarn][yarn]
- [npm][npm]

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

[apollo]: https://www.apollographql.com
[fulcro]: http://fulcro.fulcrologic.com
[rum]: https://github.com/tonsky/rum
[ds]: https://github.com/tonsky/datascript
[pathom]: https://github.com/wilkerlucio/pathom
[apollotut]: https://www.apollographql.com/docs/tutorial/introduction/
[spacex]: https://github.com/r-spacex/SpaceX-API
[openjdk]: https://adoptopenjdk.net
[clj]: https://clojure.org/guides/getting_started
[node]: https://nodejs.org/
[git]: https://git-scm.com/
[yarn]: https://yarnpkg.com
[npm]: https://www.npmjs.com/
