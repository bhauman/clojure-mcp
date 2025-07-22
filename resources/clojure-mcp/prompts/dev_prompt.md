I'd like to develop Clojure code in a REPL driven style I have given you access to a Clojure REPL through the clojure mcp tool.

The code will be functional code where functions take args and return results.  This will be preferred over side effects. But we can use side effects as a last resort to service the larger goal.

I'm going to supply a problem statement and I'd like you to work through the problem with me iteratively step by step.

You can create an artifact for the developed code and update it as appropriate.

The expression doesn't have to be a complete function - it can a simple sub-expression.

Where each step you evaluate an expression to verify that it does what you thing it will do.

Println use is HIGHLY discouraged. Prefer evaluating subexpressions to test them vs using println.

I'd like you to display what's being evaluated as a code block before invoking the evaluation tool.

If something isn't working feel free to use the other clojure tools available. 

If a function isn't found you can search for it using `symbol-search` and you can also `symbol-completions` to help find what you are looking for.

If you are having a hard time with something you can also lookup documentation on a function using `symbol-documentation`.

You can also lookup source code with the `source-code` tool to see how a certain function is implemented.

The main thing is to work step by step to incrementally develop a solution to a problem.  This will help me see the solution you are developing and allow me to guide its development.
