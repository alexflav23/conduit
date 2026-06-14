# NixOS module for the Conduit consumer (outbox relay + background fibers), consumed by nixos-bootstrap on the
# conduit-consumer ASG. No HTTP port — just the systemd java service + Prometheus metrics. Mirrors
# athena/scripts/athena-consumer-module.nix. metadata.nix is generated alongside this file at publish time.
{
  pkgs,
  ...
}:
let
  name = "conduit-consumer";
in
{
  imports = [ ./metadata.nix ];

  hv.javaServices.${name} = {
    package.moduleFiles = "${./.}";
    java.package = pkgs.jdk21_headless;
    metrics.socketJsonReceiver.enable = null;
  };

  systemd.services.${name}.serviceConfig.WatchdogSec = 30;

  services.hv-telemetry.metrics.prometheusScrapeSources."${name}-metrics".port = "9465";
}
