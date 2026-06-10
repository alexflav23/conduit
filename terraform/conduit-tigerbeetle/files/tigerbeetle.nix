# This thing gets put into nixos modules.d by terraform, it is a nixos module
{ pkgs, lib, config, ... }:
let
  tigerbeetleScript = import ./tigerbeetle-script.nix { inherit pkgs lib config; };
  name = "tigerbeetle";
in {

  imports = [ ./tigerbeetle-ebs-volume.nix ];

  users = {
    users.${name} = {
      isSystemUser = true;
      group = config.users.groups.${name}.name;
    };
    groups.${name} = {};
  };

  systemd.services.${name} = {
    wantedBy = ["multi-user.target"];

    serviceConfig = {
      User = config.users.users.${name}.name;
      Group = config.users.users.${name}.group;
      StateDirectory = "${name}";

      #  From https://docs.tigerbeetle.com/operating/deploying/systemd/#deploying-with-systemd
      DevicePolicy = "closed";
      # DynamicUser=true;
      LockPersonality=true;
      ProtectClock=true;
      ProtectControlGroups=true;
      ProtectHome=true;
      ProtectHostname=true;
      ProtectKernelLogs=true;
      ProtectKernelModules=true;
      ProtectKernelTunables=true;
    };

    # getExe will get the right path for our bin script
    script = "exec ${lib.getExe tigerbeetleScript}";
  };

}
