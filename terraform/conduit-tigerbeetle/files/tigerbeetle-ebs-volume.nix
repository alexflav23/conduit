{ config, ... }:
let
  inherit (builtins) fromJSON readFile;
  inherit (config.lib.ec2Meta) readMeta;

  #stateDir is systemd terminology
  stateDirRoot = "/var/lib";
  stateDir = "tigerbeetle";

  d = "${stateDirRoot}/${stateDir}";

  settings = fromJSON (readFile ./tigerbeetle-settings.json);
  volumeId = settings.volume_ids.${readMeta "local-ipv4"};
in
{
  ebsFileSystems.${d} = { inherit volumeId; };

  systemd.services.tigerbeetle-init = rec {
    requires = [ config.lib.ebsFileSystems.${d}.mountUnit ];
    after = requires;
  };

  systemd.services.tigerbeetle = rec {
    bindsTo = [ config.lib.ebsFileSystems.${d}.mountUnit ];
    after = bindsTo;
  };
}
