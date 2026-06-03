# Reproducible dev shell, mirroring the house pattern (Athena). nixpkgs pinned via npins.
# Local dev without Nix is supported too (homebrew sbt + JDK 19); this is what CI uses.
{
  nixpkgs ? (import ./npins).nixpkgs,
}:
let
  pkgs = import nixpkgs { };
in
pkgs.mkShell {
  nativeBuildInputs = [
    pkgs.sbt
    pkgs.temurin-bin-19
    pkgs.awscli2
    pkgs.git
    pkgs.docker-compose
  ];

  shellHook = ''
    >&2 echo "Conduit dev shell — sbt $(${pkgs.sbt}/bin/sbt --version 2>/dev/null | head -1)"
    >&2 echo "  sbt compile | sbt test | sbt api/run | docker compose up -d"
  '';
}
