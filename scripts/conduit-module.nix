# NixOS module for the Conduit API, consumed by nixos-bootstrap on the conduit-api ASG. Runs the staged
# launcher as a systemd java service (hv.javaServices), registers it in Consul, and exposes Prometheus
# metrics. Mirrors athena/scripts/athena-module.nix (minus athena's pcap capture). metadata.nix is
# generated alongside this file by scripts/module-metadata at publish time.
{
  pkgs,
  ...
}:
let
  namePrefix = "conduit";
  name = "${namePrefix}-api";
in
{
  imports = [ ./metadata.nix ];

  hv.javaServices.${name} = {
    package.moduleFiles = "${./.}";
    java.package = pkgs.jdk21_headless;
    metrics = {
      metadataName = namePrefix;
      socketJsonReceiver.enable = null;
    };
  };

  systemd.services.${name}.serviceConfig.WatchdogSec = 30;

  services.hv-telemetry.metrics.prometheusScrapeSources."${namePrefix}-metrics".port = "9464";

  # API health/admin is on :9990 (GET /health -> "OK"); the app itself listens on :8080.
  services.consul.services."${namePrefix}" = {
    port = 8080;
    checks.ready = {
      interval = "10s";
      timeout = "1s";
      port = 9990;
      httpPath = "/health";
    };
  };
}
