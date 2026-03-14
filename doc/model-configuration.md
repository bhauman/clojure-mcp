# Configuring Custom Models in Clojure MCP

The Clojure MCP system supports user-defined model configurations through the `.clojure-mcp/config.edn` file. This allows you to define reusable model configurations that can be referenced by name throughout your project.

## Overview

Custom models are defined under the `:models` key in your project's configuration file. Each model is identified by a namespaced keyword and contains a configuration map with model parameters.

## Configuration Location

```
your-project/
├── .clojure-mcp/
│   └── config.edn    # Your configuration file
├── src/
└── deps.edn
```

## Basic Configuration

Add a `:models` map to your `.clojure-mcp/config.edn`:

```clojure
{;; Other configuration options...
 :allowed-directories ["."]
 
 ;; Custom model configurations
 :models {:openai/my-fast-gpt {:model-name "gpt-4o"
                                :temperature 0.3
                                :max-tokens 2048}
          
          :anthropic/my-claude {:model-name "claude-3-5-sonnet-20241022"
                                :temperature 0.7
                                :max-tokens 4096}}}
```

## Open Router to access other models with the OpenAi Client

```clojure
{:models {:openai/glm {:model-name "z-ai/glm-4.5"
                       :api-key [:env "OPENROUTER_API_KEY"]
                       :base-url "https://openrouter.ai/api/v1/chat/completions"
                       :temperature 1}}}
```

In this case keyword namespace `:openai/` is what configures the provider api.

You can also configure the provider like so:

```clojure
{:models {:glm-4-5 {:provider :openai
                    :model-name "z-ai/glm-4.5"
                    :api-key [:env "OPENROUTER_API_KEY"]
                    :base-url "https://openrouter.ai/api/v1/chat/completions"
                    :temperature 1}}}
```

## Model Configuration Examples

### Basic Models

```clojure
:models {;; A fast, focused GPT-4 configuration
         :openai/my-fast-gpt {:model-name "gpt-4o"
                              :temperature 0.3
                              :max-tokens 2048}
         
         ;; A creative writing configuration
         :openai/my-creative-gpt {:model-name "gpt-4o"
                                  :temperature 1.5
                                  :max-tokens 8192}
         
         ;; A balanced Claude configuration
         :anthropic/my-claude {:model-name "claude-3-5-sonnet-20241022"
                               :temperature 0.7
                               :max-tokens 4096}}
```

### Reasoning Models

For models that support reasoning/thinking capabilities:

```clojure
:models {;; OpenAI reasoning model
         :openai/my-o3 {:model-name "o3"
                        :max-tokens 8192
                        :thinking {:effort :high}}
         
         ;; Anthropic reasoning model
         :anthropic/my-reasoning-claude {:model-name "claude-3-5-sonnet-20241022"
                                         :max-tokens 8192
                                         :thinking {:enabled true
                                                    :return true
                                                    :send true
                                                    :budget-tokens 4096}}
         
         ;; Google Gemini reasoning model
         :google/my-gemini-reasoning {:model-name "gemini-2.5-pro"
                                      :max-tokens 8192
                                      :thinking {:enabled true
                                                 :effort :medium
                                                 :budget-tokens 8192}}}
```

### Provider-Specific Features

```clojure
:models {;; Google Gemini with code execution
         :google/my-gemini-coder {:model-name "gemini-2.5-flash"
                                  :temperature 0.5
                                  :max-tokens 4096
                                  :google {:allow-code-execution true
                                           :include-code-execution-output true}}
         
         ;; Anthropic with caching
         :anthropic/my-cached-claude {:model-name "claude-3-5-sonnet-20241022"
                                      :max-tokens 4096
                                      :anthropic {:cache-system-messages true
                                                  :cache-tools true}}
         
         ;; OpenAI with organization settings
         :openai/my-org-gpt {:model-name "gpt-4o"
                             :max-tokens 4096
                             :openai {:organization-id "org-123"
                                      :project-id "proj-456"
                                      :strict-tools true}}}
```

## Using Custom Models

### From Code

Use the `create-model-from-config` function with the nREPL client map:

```clojure
(require '[clojure-mcp.agent.langchain.model :as model])

;; Create a model directly
(let [model (model/create-model-from-config nrepl-client-map :openai/my-fast-gpt)]
  ;; Use the model...
  )

;; Override parameters at runtime
(let [model (model/create-model-from-config 
             nrepl-client-map 
             :openai/my-fast-gpt
             {:temperature 0.5})] ; Override temperature
  ;; Use the model...
  )

;; Get a builder for further customization
(let [builder (model/create-model-builder-from-config 
               nrepl-client-map 
               :anthropic/my-claude)
      model (.build builder)]
  ;; Use the model...
  )
```

### Fallback Behavior

If a model key is not found in your custom configuration, the system automatically falls back to built-in defaults (if available). For example:

```clojure
;; If :openai/gpt-4o is not in your config, it uses the built-in default
(let [model (model/create-model-from-config nrepl-client-map :openai/gpt-4o)]
  ;; Uses built-in configuration
  )
```

## Available Parameters

### Common Parameters (All Providers)

- `:model-name` - The model identifier (string or enum)
- `:api-key` - API key (optional, uses environment variable if not provided)
- `:temperature` - Controls randomness (0.0-2.0)
- `:max-tokens` - Maximum tokens to generate
- `:top-p` - Nucleus sampling parameter (0.0-1.0)
- `:stop-sequences` - List of stop sequences
- `:max-retries` - Number of retry attempts
- `:timeout` - Request timeout in milliseconds
- `:log-requests` - Log API requests (boolean)
- `:log-responses` - Log API responses (boolean)

### Anthropic-Specific

- `:top-k` - Top-k sampling parameter
- `:thinking` - Thinking configuration map:
  - `:enabled` - Enable thinking mode
  - `:return` - Return thinking in response
  - `:send` - Send thinking to API
  - `:budget-tokens` - Token budget for thinking
- `:anthropic` - Provider-specific options:
  - `:version` - API version
  - `:beta` - Beta features
  - `:cache-system-messages` - Cache system messages
  - `:cache-tools` - Cache tool definitions

### Google-Specific

- `:seed` - Random seed for reproducibility
- `:frequency-penalty` - Frequency penalty (-2.0 to 2.0)
- `:presence-penalty` - Presence penalty (-2.0 to 2.0)
- `:thinking` - Thinking configuration map:
  - `:enabled` - Enable thinking mode
  - `:effort` - Effort level (:low, :medium, :high)
  - `:budget-tokens` - Token budget (or auto-calculated from effort)
- `:google` - Provider-specific options:
  - `:allow-code-execution` - Allow code execution
  - `:include-code-execution-output` - Include execution output
  - `:response-logprobs` - Include log probabilities
  - `:logprobs` - Number of logprobs to return

### OpenAI-Specific

- `:seed` - Random seed for reproducibility
- `:frequency-penalty` - Frequency penalty (-2.0 to 2.0)
- `:presence-penalty` - Presence penalty (-2.0 to 2.0)
- `:thinking` - For reasoning models:
  - `:effort` - Reasoning effort (:low, :medium, :high)
  - `:return` - Return thinking in response
- `:openai` - Provider-specific options:
  - `:organization-id` - Organization identifier
  - `:project-id` - Project identifier
  - `:max-completion-tokens` - Max completion tokens
  - `:strict-tools` - Strict tool mode
  - `:parallel-tool-calls` - Allow parallel tool calls
  - `:user` - User identifier for tracking

## Built-in Models

The system includes 55 pre-configured models that can be used without configuration:

### OpenAI
- `:openai/gpt-4o`
- `:openai/gpt-4-1`, `:openai/gpt-4-1-mini`, `:openai/gpt-4-1-nano`
- `:openai/gpt-5`, `:openai/gpt-5-mini`, `:openai/gpt-5-nano`, `:openai/gpt-5-pro`, `:openai/gpt-5-codex`
- `:openai/gpt-5-1`, `:openai/gpt-5-1-codex`, `:openai/gpt-5-1-codex-max`, `:openai/gpt-5-1-codex-mini`
- `:openai/gpt-5-2`, `:openai/gpt-5-2-pro`, `:openai/gpt-5-2-codex`
- `:openai/gpt-5-4`, `:openai/gpt-5-4-pro`
- `:openai/o1`, `:openai/o1-mini`, `:openai/o1-pro`
- `:openai/o3`, `:openai/o3-mini`, `:openai/o3-pro`
- `:openai/o4-mini`, `:openai/o4-mini-reasoning`

### Google
- `:google/gemini-2-5-flash-lite`
- `:google/gemini-2-5-pro`
- `:google/gemini-2-5-flash`
- `:google/gemini-2-5-flash-reasoning`
- `:google/gemini-2-5-pro-reasoning`
- `:google/gemini-3-flash`, `:google/gemini-3-pro`
- `:google/gemini-3-1-flash-lite`, `:google/gemini-3-1-pro`

### Mistral (via OpenAI-compatible API, requires `MISTRAL_API_KEY`)
- `:mistral/mistral-large`, `:mistral/mistral-medium`, `:mistral/mistral-small`
- `:mistral/codestral`, `:mistral/devstral`
- `:mistral/magistral-medium`, `:mistral/magistral-small`

### Anthropic
- `:anthropic/claude-opus-4-6`, `:anthropic/claude-opus-4-6-reasoning`
- `:anthropic/claude-sonnet-4-6`, `:anthropic/claude-sonnet-4-6-reasoning`
- `:anthropic/claude-opus-4-1`, `:anthropic/claude-opus-4-1-reasoning`
- `:anthropic/claude-opus-4`, `:anthropic/claude-opus-4-reasoning`
- `:anthropic/claude-sonnet-4-5`, `:anthropic/claude-sonnet-4-5-reasoning`
- `:anthropic/claude-sonnet-4`, `:anthropic/claude-sonnet-4-reasoning`
- `:anthropic/claude-haiku-4-5`

## API Keys

API keys can be provided in three ways (in order of precedence):

1. In the model configuration: `:api-key "your-key"`
2. As a runtime override when creating the model
3. From environment variables:
   - OpenAI: `OPENAI_API_KEY`
   - Google: `GEMINI_API_KEY`
   - Anthropic: `ANTHROPIC_API_KEY`
   - Mistral: `MISTRAL_API_KEY`

## Validation

By default, all configurations are validated against Clojure specs. To disable validation:

```clojure
(model/create-model-from-config 
  nrepl-client-map 
  :openai/my-model
  {}
  {:validate? false})
```

## Complete Example

Here's a complete `.clojure-mcp/config.edn` with various model configurations:

```clojure
{;; File access permissions
 :allowed-directories ["."]
 
 ;; Other configuration options
 :cljfmt true
 :bash-over-nrepl true
 
 ;; Model configurations
 :models {;; Task-specific OpenAI models
          :openai/code-reviewer {:model-name "gpt-4o"
                                 :temperature 0.2
                                 :max-tokens 4096}
          
          :openai/creative-writer {:model-name "gpt-4o"
                                   :temperature 1.2
                                   :max-tokens 8192
                                   :top-p 0.95}
          
          :openai/reasoner {:model-name "o3"
                           :max-tokens 16384
                           :thinking {:effort :high}}
          
          ;; Anthropic models for different use cases
          :anthropic/analyzer {:model-name "claude-3-5-sonnet-20241022"
                               :temperature 0.3
                               :max-tokens 4096}
          
          :anthropic/deep-thinker {:model-name "claude-3-5-sonnet-20241022"
                                   :max-tokens 8192
                                   :thinking {:enabled true
                                             :return true
                                             :send true
                                             :budget-tokens 8192}
                                   :anthropic {:cache-system-messages true}}
          
          ;; Google Gemini for code execution tasks
          :google/code-runner {:model-name "gemini-2.5-flash"
                              :temperature 0.1
                              :max-tokens 4096
                              :google {:allow-code-execution true
                                      :include-code-execution-output true}}}}
```
