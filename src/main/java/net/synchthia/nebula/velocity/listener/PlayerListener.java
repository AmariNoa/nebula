package net.synchthia.nebula.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.synchthia.nebula.api.NebulaProtos;
import net.synchthia.nebula.velocity.NebulaVelocityPlugin;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PlayerListener {
    private final NebulaVelocityPlugin plugin;

    public PlayerListener(NebulaVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!NebulaVelocityPlugin.getIsIPFilterEnable()) {
            return;
        }

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try {
                InetSocketAddress address = event.getConnection().getRemoteAddress();
                String ipAddress = address.toString().split(":")[0].replaceAll("/", "");
                plugin.getLogger().info("Trying lookup IP: " + ipAddress);

                NebulaProtos.IPLookupResponse response = plugin.getPlayerAPI().lookupIP(ipAddress).get(5, TimeUnit.SECONDS);
                NebulaProtos.IPLookupResult result = response.getResult();
                if (result.getIsSuspicious()) {
                    Component prefix = MiniMessage.miniMessage().deserialize("<white>-<red><bold> ERROR <white>-\n\n");
                    Component msgEn = MiniMessage.miniMessage().deserialize("<red>Login Failed: Connection Blocked.\n");
                    Component msgJa = MiniMessage.miniMessage().deserialize("<red>ログインに失敗しました: 接続拒否\n");
                    Component errCode = MiniMessage.miniMessage().deserialize("<darK_gray>\n[ERR_LOGIN_BLOCKED]");
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                            Component.join(JoinConfiguration.noSeparators(), prefix, msgEn, msgJa, errCode)
                    ));

                    plugin.getLogger().info(String.format("Login denied (IP Filter) %s from %s / reason: %s", event.getUsername(), ipAddress, result.getReason()));
                }

            } catch (InterruptedException | ExecutionException | TimeoutException ex) {
                // NOTE: Exception handled, but denial login process (for prevent login issue when limitations)
                plugin.getLogger().error("Exception threw execution onPreLogin", ex);
            }
        }).schedule();
    }

    @Subscribe
    public void onLogin(PlayerChooseInitialServerEvent event) {
        Optional<NebulaProtos.ServerEntry> lobbyEntry = plugin.getServerAPI().determinateLobby();
        if (lobbyEntry.isEmpty()) {
            Component prefix = MiniMessage.miniMessage().deserialize("<white>-<red><bold> ERROR <white>-\n\n");
            Component msgEn = MiniMessage.miniMessage().deserialize("<red>Login Failed: Couldn't find available Lobby.\n");
            Component msgJa = MiniMessage.miniMessage().deserialize("<red>ログインに失敗しました: 接続可能なロビーがありません。\n");
            Component errCode = MiniMessage.miniMessage().deserialize("<darK_gray>\n[ERR_DETERMINATE_LOBBY]");

            event.getPlayer().disconnect(Component.empty().append(prefix).append(msgEn).append(msgJa).append(errCode));
            plugin.getLogger().error("Couldn't pass event: PlayerChooseInitialServerEvent (failed determinate lobby)");
            return;
        }

        Optional<RegisteredServer> lobbyServer = plugin.getServer().getServer(lobbyEntry.get().getName());
        event.setInitialServer(lobbyServer.get());
    }

    @Subscribe
    public void onServerKick(KickedFromServerEvent event) {
        ServerInfo kickedFrom = event.getServer().getServerInfo();
        NebulaProtos.ServerEntry kickedFromEntry = plugin.getServerAPI().getServer(kickedFrom.getName());
        Component disconnectPrefix = MiniMessage.miniMessage().deserialize("<white>-<blue><bold> INFORMATION <white>-\n\n");
        Component kickedPrefix = MiniMessage.miniMessage().deserialize("<red><bold>" + kickedFromEntry.getDisplayName() + " <gray>≫<reset> ");

        Optional<ServerConnection> currentServer = event.getPlayer().getCurrentServer();
        Optional<NebulaProtos.ServerEntry> lobbyEntry = plugin.getServerAPI().determinateLobby();
        Optional<RegisteredServer> lobby = lobbyEntry.isPresent() ? plugin.getServer().getServer(lobbyEntry.get().getName()) : Optional.empty();

        if (lobby.isEmpty()) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(disconnectPrefix.append(event.getServerKickReason().orElse(Component.empty()))));
            return;
        }

        // When couldn't connect to or kicked from lobby server
        if (currentServer.isEmpty()) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(disconnectPrefix.append(event.getServerKickReason().orElse(Component.empty()))));
            return;
        }

        // Player in Kicked Server (Should be Move Server)
        if (currentServer.get().getServerInfo().getName().equals(kickedFromEntry.getName())) {
            event.setResult(KickedFromServerEvent.Notify.create(kickedPrefix.append(event.getServerKickReason().orElse(Component.empty()))));

            // Player in Fallback Server (Should be Kick Network)
            if (plugin.getServerAPI().getServer(currentServer.get().getServerInfo().getName()).getFallback()) {
                // Kick Server
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(disconnectPrefix.append(event.getServerKickReason().orElse(Component.empty()))));
            } else {
                // Move Server
                lobby.ifPresent(registeredServer -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(registeredServer, kickedPrefix.append(event.getServerKickReason().orElse(Component.empty())))));
            }
        } else {
            event.setResult(KickedFromServerEvent.Notify.create(kickedPrefix.append(event.getServerKickReason().orElse(Component.empty()))));
        }

        plugin.getLogger().info(String.format("[%s / %s] Kicked from %s: %s", event.getResult(), event.getPlayer().getUsername(), event.getServer().getServerInfo().getName(), MiniMessage.miniMessage().serialize(event.getServerKickReason().orElse(Component.empty()))));
    }

    @Subscribe
    public void onServerDisconnected(DisconnectEvent event) {
        Set<NebulaProtos.PlayerProperty> properties = plugin.getPlayerAPI().profileToProperties(event.getPlayer().getGameProfile());

        plugin.getPlayerAPI().requestPlayerQuit(NebulaProtos.PlayerProfile.newBuilder()
                .setPlayerUUID(event.getPlayer().getUniqueId().toString())
                .setPlayerName(event.getPlayer().getUsername())
                .setPlayerLatency(event.getPlayer().getPing())
                .addAllProperties(properties)
                .build());
    }
}
