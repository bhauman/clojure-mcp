# Creating Your Own Custom MCP Server

**🎉 Welcome to the fun part!** Creating your own custom MCP server is not just easy—it's empowering and delightful. You get to craft a personalized AI assistant that understands YOUR workflow, YOUR project structure, and YOUR development style. During the alpha phase of clojure-mcp, creating your own main entry point is the primary way to configure the server, giving you complete control over your development experience.

Think of it as building your own personalized AI development companion. Want only read-only tools for safer exploration? Done. Need specialized ClojureScript tools? Add them in. Have custom prompts for your team's coding standards? Perfect. This is YOUR server, tailored to YOUR needs.

> **💡 Pro Tip**: Always refer to `src/clojure_mcp/main.clj` to see the current optimized set of tools, resources, and prompts. This file represents the carefully curated default configuration and serves as the best reference for understanding which tools work well together and how they're organized.

## Why Create a Custom Server?

- **Personalization**: Include only the tools you actually use
- **Custom Workflows**: Add prompts specific to your project or team
- **Specialized Resources**: Expose your project's unique documentation
- **Tool Integration**: Add tools for your specific tech stack (Shadow-cljs, Figwheel, etc.)
- **Safety Controls**: Choose between read-only exploration or full editing capabilities
- **Performance**: Smaller tool sets mean faster startup and less cognitive overhead

## The New Simplified Pattern (as of v0.5.0)

The beauty of the refactored clojure-mcp is that creating custom servers is now incredibly simple. The new pattern uses factory functions that create tools, prompts, and resources. Here's how it works:

1. **Define factory functions** for tools, prompts, and/or resources
2. **Call** `core/build-and-start-mcp-server` with your factories
3. **That's it!** The core handles all the complex setup

## Minimal Custom Server Example

Let's start with the simplest possible custom server that reuses everything from `main`:

```clojure
(ns my-company.mcp-server
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
```

That's it! You now have a fully functional MCP server using all the standard components.

## Customizing Resources

Want to add your own documentation? Create your own `make-resources` function:

```clojure
(ns my-company.mcp-server
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]
            [clojure-mcp.resources :as resources]
            [clojure.java.io :as io]))

(defn make-resources [nrepl-client-atom working-dir]
  ;; Start with the default resources
  (concat
   (main/make-resources nrepl-client-atom working-dir)
   ;; Add your custom resources
   [(resources/create-file-resource
     "custom://architecture"
     "ARCHITECTURE.md"
     "Our system architecture documentation"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "docs/ARCHITECTURE.md")))
    
    (resources/create-string-resource
     "custom://team-standards"
     "Team Coding Standards"
     "Our team's Clojure coding standards"
     "text/markdown"
     "# Team Coding Standards\n\n- Always use kebab-case\n- Prefer threading macros\n- Write tests first")]))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn make-resources}))  ; Use our custom resources
```

## Customizing Tools

### Selective Tool Loading

Maybe you want a read-only server for safer exploration:

```clojure
(ns my-company.read-only-server
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]
            ;; Import only the tools you need
            [clojure-mcp.tools.directory-tree.tool :as directory-tree-tool]
            [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
            [clojure-mcp.tools.grep.tool :as new-grep-tool]
            [clojure-mcp.tools.glob-files.tool :as glob-files-tool]
            [clojure-mcp.tools.think.tool :as think-tool]
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.tools.project.tool :as project-tool]))

(defn make-read-only-tools [nrepl-client-atom working-directory]
  ;; Only include read-only and evaluation tools
  [(directory-tree-tool/directory-tree-tool nrepl-client-atom)
   (unified-read-file-tool/unified-read-file-tool nrepl-client-atom)
   (new-grep-tool/grep-tool nrepl-client-atom)
   (glob-files-tool/glob-files-tool nrepl-client-atom)
   (think-tool/think-tool nrepl-client-atom)
   (eval-tool/eval-code nrepl-client-atom)
   (project-tool/inspect-project-tool nrepl-client-atom)])

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-read-only-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
```

### Adding Custom Tools

Have a custom tool? Add it to the mix:

```clojure
(ns my-company.mcp-server
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]
            [my-company.database-tool :as db-tool]))

(defn make-tools [nrepl-client-atom working-directory]
  ;; Start with main tools and add your own
  (conj (main/make-tools nrepl-client-atom working-directory)
        (db-tool/database-query-tool nrepl-client-atom)))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
```

## Custom Prompts

Add project-specific prompts to guide your AI assistant:

```clojure
(defn make-prompts [nrepl-client-atom working-dir]
  ;; Start with default prompts
  (concat
   (main/make-prompts nrepl-client-atom working-dir)
   ;; Add custom prompts
   [{:name "database-migration"
     :description "Generate database migration code"
     :arguments [{:name "table-name"
                  :description "Name of the table to migrate"
                  :required? true}
                 {:name "operation"
                  :description "add-column, remove-column, create-table, etc."
                  :required? true}]
     :prompt-fn (fn [_ args callback]
                  (callback
                   {:description "Database migration assistant"
                    :messages [{:role :user
                                :content (str "Generate a database migration for: "
                                            (get args "table-name")
                                            " operation: " (get args "operation")
                                            "\nUse our standard migration format.")}]}))}
    
    {:name "test-generator"
     :description "Generate test cases for a namespace"
     :arguments [{:name "namespace"
                  :description "The namespace to test"
                  :required? true}]
     :prompt-fn (fn [_ args callback]
                  (let [ns-name (get args "namespace")]
                    (callback
                     {:description "Test generation"
                      :messages [{:role :user
                                  :content (str "Please generate comprehensive tests for: " ns-name
                                              "\n\nInclude:"
                                              "\n- Unit tests for each public function"
                                              "\n- Property-based tests where appropriate"
                                              "\n- Edge cases and error conditions"
                                              "\n- Use our team's test naming conventions")}]})))}]))
```

## Modifying Existing Tools, Resources, and Prompts

Sometimes you need to modify existing components rather than creating new ones. Common reasons include:
- Resolving name conflicts between tools
- Changing descriptions to influence how AI assistants use them
- Customizing behavior for your specific workflow

### Modifying Tool Names and Descriptions

Tools are just maps, so you can modify them in your factory function:

```clojure
(defn make-tools [nrepl-client-atom working-directory]
  (let [standard-tools (main/make-tools nrepl-client-atom working-directory)]
    ;; Find and modify specific tools
    (map (fn [tool]
           (case (:name tool)
             ;; Rename bash to be more specific
             "bash" (assoc tool 
                          :name "shell_command"
                          :description "Execute shell commands in the project directory. Use for: git operations, running tests, file system operations.")
             
             ;; Make file reading more prominent
             "read_file" (assoc tool
                               :description "READ FILES FIRST! Always use this before editing. Smart reader with pattern matching for Clojure files.")
             
             ;; Discourage use of file_edit in favor of clojure_edit
             "file_edit" (assoc tool
                               :description "Simple text replacement - AVOID for Clojure files! Use clojure_edit instead.")
             
             ;; Return unchanged
             tool))
         standard-tools)))
```

### Adding Prefixes to Avoid Conflicts

If you're combining tools from multiple sources:

```clojure
(defn prefix-tool-names [prefix tools]
  (map #(update % :name (fn [n] (str prefix "_" n))) tools))

(defn make-tools [nrepl-client-atom working-directory]
  (concat
   ;; Standard tools with prefix
   (prefix-tool-names "core" (main/make-tools nrepl-client-atom working-directory))
   
   ;; Your custom tools with different prefix
   (prefix-tool-names "custom" 
                      [(my-special-tool/special-tool nrepl-client-atom)])))
```

### Complete Example: Customizing Everything

Here's how to selectively modify components while keeping what you want:

```clojure
(ns my-company.custom-mcp-server
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]
            [clojure-mcp.resources :as resources]
            [clojure.string :as str]))

(defn customize-for-safety [tool]
  ;; Make all editing tools warn about safety
  (if (str/includes? (:name tool) "edit")
    (update tool :description 
            #(str "⚠️ CAUTION: This modifies files! " %))
    tool))

(defn make-tools [nrepl-client-atom working-directory]
  (->> (main/make-tools nrepl-client-atom working-directory)
       ;; Remove tools we don't want
       (remove #(= (:name %) "bash"))  ; Too dangerous
       ;; Modify remaining tools
       (map customize-for-safety)
       ;; Rename potential conflicts
       (map (fn [tool]
              (case (:name tool)
                "think" (assoc tool :name "reflect")  ; Avoid conflict with other system
                tool)))))

(defn make-resources [nrepl-client-atom working-dir]
  (let [standard-resources (main/make-resources nrepl-client-atom working-dir)]
    (concat
     ;; Modify existing resources
     (map (fn [resource]
            (case (:name resource)
              ;; Make project summary more prominent
              "PROJECT_SUMMARY.md" 
              (assoc resource 
                     :name "MAIN_PROJECT_CONTEXT"
                     :description "CRITICAL: Primary project documentation - ALWAYS load this first!")
              
              ;; Keep others as-is
              resource))
          standard-resources)
     
     ;; Add your own
     [(resources/create-file-resource
       "custom://runbook"
       "RUNBOOK.md"
       "Emergency procedures and runbook"
       "text/markdown"
       (str working-dir "/docs/RUNBOOK.md"))])))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn make-resources}))
```

## Real-World Example: Shadow-cljs Server

Here's how the Shadow-cljs example extends the main server:

```clojure
(ns my-company.shadow-mcp
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.nrepl :as nrepl]
            [clojure.tools.logging :as log]))

;; ... shadow-specific tool implementation ...

(defn make-tools [nrepl-client-atom working-directory & [{:keys [port shadow-port shadow-build shadow-watch] :as config}]]
  (if (and port shadow-port (not= port shadow-port))
    (conj (main/make-tools nrepl-client-atom working-directory)
          (shadow-eval-tool-secondary-connection-tool nrepl-client-atom config))
    (conj (main/make-tools nrepl-client-atom working-directory)
          (shadow-eval-tool nrepl-client-atom config))))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn (fn [nrepl-client-atom working-directory]
                     (make-tools nrepl-client-atom working-directory opts))
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
```

## Complete Custom Server Template

Here's a full template you can use as a starting point:

```clojure
(ns my-company.custom-mcp-server
  "Custom MCP server tailored for our team's Clojure development"
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]
            [clojure-mcp.resources :as resources]
            [clojure-mcp.prompts :as prompts]
            ;; Add specific tool requires as needed
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.tools.unified-read-file.tool :as read-tool]
            [clojure.java.io :as io]))

(defn make-resources
  "Custom resources including our team documentation"
  [nrepl-client-atom working-dir]
  (concat
   ;; Include some defaults
   [(first (main/make-resources nrepl-client-atom working-dir))] ; PROJECT_SUMMARY
   ;; Add our custom resources
   [(resources/create-file-resource
     "custom://style-guide"
     "STYLE_GUIDE.md"
     "Our comprehensive Clojure style guide"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "docs/STYLE_GUIDE.md")))]))

(defn make-prompts
  "Custom prompts for our workflow"
  [nrepl-client-atom working-dir]
  [{:name "pr-review"
    :description "Review code changes for a pull request"
    :arguments []
    :prompt-fn (prompts/simple-content-prompt-fn
                "PR Review Guide"
                "Please review the recent changes focusing on:
                 1. Our team's style guide compliance
                 2. Test coverage
                 3. Performance implications
                 4. API compatibility")}])

(defn make-tools
  "Curated tool selection for our team"
  [nrepl-client-atom working-directory]
  ;; Mix and match from main tools or add your own
  [(eval-tool/eval-code nrepl-client-atom)
   (read-tool/unified-read-file-tool nrepl-client-atom)
   ;; ... add more tools as needed
   ])

(defn start-mcp-server
  "Start our custom MCP server"
  [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-tools
    :make-prompts-fn make-prompts
    :make-resources-fn make-resources}))
```

## Configuring deps.edn

Point your deps.edn to your custom server:

```clojure
{:aliases 
  {:my-mcp 
    {:deps {org.slf4j/slf4j-nop {:mvn/version "2.0.16"}
            com.bhauman/clojure-mcp {:local/root "~/workspace/clojure-mcp"}}
     :extra-paths ["src"] ; Where your custom server lives
     :exec-fn my-company.custom-mcp-server/start-mcp-server
     :exec-args {:port 7888}}}}
```

## Tips for Success

1. **Start Simple**: Begin by reusing main's factory functions, then gradually customize
2. **Test Incrementally**: Add one customization at a time and test
3. **Document Your Choices**: Comment why you included/excluded specific tools
4. **Version Control**: Keep your custom server in version control
5. **Team Sharing**: Share your server configuration with your team
6. **Factory Function Signatures**: Always use `[nrepl-client-atom working-directory]` for your factory functions

## Common Patterns

### Development vs Production Servers

```clojure
(defn make-dev-tools [nrepl-client-atom working-directory]
  ;; All tools including editing
  (main/make-tools nrepl-client-atom working-directory))

(defn make-prod-tools [nrepl-client-atom working-directory]
  ;; Read-only tools for production debugging
  [(read-tool/unified-read-file-tool nrepl-client-atom)
   (eval-tool/eval-code nrepl-client-atom)])

(defn start-mcp-server [{:keys [env] :as opts}]
  (let [tools-fn (if (= env "production") make-prod-tools make-dev-tools)]
    (core/build-and-start-mcp-server
     opts
     {:make-tools-fn tools-fn
      :make-prompts-fn main/make-prompts
      :make-resources-fn main/make-resources})))
```

### Project-Type Specific Servers

```clojure
(defn make-web-app-tools [nrepl-client-atom working-directory]
  ;; Tools for web development
  (concat
   (main/make-tools nrepl-client-atom working-directory)
   [(http-tool/http-client-tool nrepl-client-atom)]))

(defn make-library-tools [nrepl-client-atom working-directory]
  ;; Tools for library development - focus on docs and API design
  (concat
   [(doc-tool/documentation-tool nrepl-client-atom)]
   (main/make-tools nrepl-client-atom working-directory)))
```

### Using Alternative Transports

The new pattern also supports different transport mechanisms. For example, using SSE (Server-Sent Events):

```clojure
(ns my-company.sse-server
  (:require [clojure-mcp.main :as main]
            [clojure-mcp.sse-core :as sse-core]))

(defn start-sse-mcp-server [opts]
  ;; Use SSE transport instead of stdio
  (sse-core/build-and-start-mcp-server
   opts
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
```

## Conclusion

Creating your own custom MCP server with the new pattern is simpler than ever. The factory function approach means you can:

1. Start with a one-function server that reuses everything
2. Gradually customize by replacing individual factory functions
3. Mix and match components from different sources
4. Easily maintain and share your configuration

Remember: during the alpha phase, this IS the way to configure clojure-mcp. The new pattern makes it so easy there's no reason not to create your perfect development environment!

Happy customizing! 🚀
