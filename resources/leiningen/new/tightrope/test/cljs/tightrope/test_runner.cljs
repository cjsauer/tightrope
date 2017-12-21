(ns {{namespace}}.test-runner
  (:require  [doo.runner :refer-macros [doo-tests]]
             [{{namespace}}.core-test]))

(doo-tests '{{namespace}}.core-test)
