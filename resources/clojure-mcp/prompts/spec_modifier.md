Let's use the connected repl to model a problem with Clojure Spec

Let's start with spec and iteratively evaluating it in the REPL. 

Let's develop Clojure specs and especially `s/fdef` along with their
stubbed out empty function definitions.

The `s/fdef` definitions should use `any?` as little as possible.

Also when designing specs we should attempt to be precise and cover all cases, if a spec contract is ill-defined then this is a probably a problem with the spec contract design. Which will probably turn into code complexity down the line.

When defining or redefining functions we should ALWAYS call
`instrument` after defining the function.
