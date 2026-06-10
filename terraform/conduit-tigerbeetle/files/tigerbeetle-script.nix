{ pkgs, lib, config }:
let
  inherit (builtins) concatStringsSep length;
  inherit (config.lib) ec2Meta;

  myIp     = ec2Meta.readMeta "local-ipv4";
  settings = lib.importJSON "/etc/nixos/modules.d/tigerbeetle/tigerbeetle-settings.json";

  replicaIps      = settings.ips;
  tigerbeetlePort = toString settings.port;
  addressList     = map (ip: ip + ":" + tigerbeetlePort) replicaIps;
  replicaId       = settings.replica_ids.${myIp};
  volumeId        = replicaId;
  clusterId       = toString settings.cluster_id;
  replicaCount    = toString (length replicaIps);
  addresses       = concatStringsSep "," addressList;

  dataFile = "/var/lib/tigerbeetle/${clusterId}_${volumeId}.tigerbeetle";

  version = "0.16.46";

  tigerbeetle = pkgs.stdenv.mkDerivation rec {
    pname   = "tigerbeetle";
    version = "0.16.46";

    src = pkgs.fetchzip {
       url  = "https://github.com/tigerbeetle/tigerbeetle/releases/download/${version}/tigerbeetle-aarch64-linux.zip";
       hash = "sha256-54z/wuwGix2ktyuHd7yc+b5de/i+CDuC1G4CwJbGOEA=";
     };
    buildInputs = [ pkgs.unzip ];
    unpackCmd   = "unzip $curSrc -d src";
    installPhase = ''
      mkdir -p $out/bin
      mv tigerbeetle $out/bin
    '';
  };
in
  pkgs.writeShellScriptBin "startTigerbeetleReplica" ''

  ${tigerbeetle}/bin/tigerbeetle format --cluster=${clusterId} --replica-count=${replicaCount} --replica=${volumeId} ${dataFile}
  ${tigerbeetle}/bin/tigerbeetle start --addresses=${addresses} ./${dataFile}

''