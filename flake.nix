{
  description = "Clojure MCP flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs = { self, nixpkgs, flake-utils, clj-nix }:

    flake-utils.lib.eachDefaultSystem
      (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          cljpkgs = clj-nix.packages."${system}";
        in
        {
          packages = rec {
            default = clojure-mcp;

            clojure-mcp = cljpkgs.mkCljBin {
              projectSrc = ./.;
              name = "com.github.bhauman/clojure-mcp";
              main-ns = "clojure-mcp.main";
              compileCljOpts = {
                ns-compile = ["clojure-mcp.main"];
              };
              jdkRunner = pkgs.jdk17_headless;
            };
          };
          devShells.default =
            let
              deps-lock-update = pkgs.writeShellApplication {
                name = "deps-lock-update";
                runtimeInputs = [ cljpkgs.deps-lock ];
                text = "deps-lock --deps-include deps.edn --alias-include mcp";
              };
            in
            pkgs.mkShell
              {
                packages = [ deps-lock-update ];
              };
        }) // {
      overlays.default = (final: prev: {
        clojure-mcp = self.packages.${final.system}.default;
      });
    };
}
