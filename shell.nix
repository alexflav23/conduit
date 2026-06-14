# Reproducible dev shell, mirroring the house pattern (Athena). nixpkgs pinned via npins.
# Local dev without Nix is supported too (homebrew sbt + JDK 21); this is what CI uses.
# JDK 21 (current LTS): Temurin 19 was non-LTS and is removed from nixpkgs at EOL.
{
  nixpkgs ? (import ./npins).nixpkgs,
}:
let
  pkgs = import nixpkgs { };
in
pkgs.mkShell {
  nativeBuildInputs = [
    pkgs.sbt
    pkgs.temurin-bin-21
    pkgs.awscli2
    pkgs.git
    pkgs.docker-compose
  ];

  shellHook = ''
    >&2 echo "Conduit dev shell — sbt $(${pkgs.sbt}/bin/sbt --version 2>/dev/null | head -1)"
    >&2 echo "  sbt compile | sbt test | sbt api/run | docker compose up -d"
  '';
}
