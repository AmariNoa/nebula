package net.synchthia.nebula.bukkit.tablist;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.*;
import lombok.RequiredArgsConstructor;
import net.synchthia.nebula.api.player.PlayerProperty;
import net.synchthia.nebula.bukkit.NebulaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

@RequiredArgsConstructor
public class TabList {
    private final NebulaPlugin plugin;

    // 現在のプレイヤーリスト (APIから取得同期)
    private final Map<UUID, TabListEntry> tabListEntries = new HashMap<>();

    // プレイヤーごとのTabListデータ (再送回避用)
    private final Map<Player, Set<UUID>> sentPlayers = new HashMap<>();

    // プレイヤーのチャットセッション（同期なし）
    private final Map<UUID, WrappedRemoteChatSessionData> publicKeys = new HashMap<>();

    public void registerListener() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.addPacketListener(new PacketAdapter(plugin, List.of(PacketType.Play.Server.PLAYER_INFO)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                final PacketContainer packet = event.getPacket();
                final Set<EnumWrappers.PlayerInfoAction> actions = packet.getPlayerInfoActions().read(0);
                final boolean addPlayer = actions.contains(EnumWrappers.PlayerInfoAction.ADD_PLAYER);
                final boolean initializeChat = actions.contains(EnumWrappers.PlayerInfoAction.INITIALIZE_CHAT);
                if (initializeChat) {
                    if (actions.size() != 1 && !addPlayer) {
                        event.setCancelled(true);
                    }

                    for (final PlayerInfoData data : packet.getPlayerInfoDataLists().read(1)) {
                        publicKeys.put(data.getProfileId(), data.getRemoteChatSessionData());
                    }
                } else if (!addPlayer) {
                    event.setCancelled(true);
                }
//                event.setCancelled(true);
//                if (event.getPacketType().equals(PacketType.Play.Server.PLAYER_INFO)) {
//                    PacketContainer packet = event.getPacket();
//                    packet.getPlayerInfoActions().write(0, EnumSet.of(
//                            EnumWrappers.PlayerInfoAction.INITIALIZE_CHAT
//                    ));
//                }
            }
        });
        manager.addPacketListener(new PacketAdapter(plugin, List.of(PacketType.Play.Server.PLAYER_INFO_REMOVE)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                event.setCancelled(true);
            }
        });
    }

    public void removeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendRemoveAllPacket(player);
        }
        tabListEntries.clear();
    }

    public void addPlayer(TabListEntry entry) {
        tabListEntries.remove(entry.getUuid());
        tabListEntries.put(entry.getUuid(), entry);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (entry.isHide()) {
                sendRemovePacket(player, entry.getUuid());
            } else {
                sendAddPacket(player, entry);
            }
        }
    }

    public void removePlayer(UUID uuid) {
        if (!tabListEntries.containsKey(uuid)) {
            return;
        }

        tabListEntries.remove(uuid);
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sendRemovePacket(player, uuid);
            });
        }
    }

    public void updatePlayer(Map<UUID, TabListEntry> entries) {
        entries.forEach(((uuid, tabListEntry) -> {
            this.addPlayer(tabListEntry);
        }));
    }

    public void syncTabList(Player player) {
        sendRemoveAllPacket(player);
        for (TabListEntry entry : tabListEntries.values()) {
            if (entry.isHide()) {
                sendRemovePacket(player, entry.getUuid());
            } else {
                sendAddPacket(player, entry);
            }
        }
    }

    public void desyncTabList(Player player) {
        sentPlayers.remove(player);
        publicKeys.remove(player.getUniqueId());
    }

    public void sendRemovePacket(Player player, UUID uuid) {
        final PacketContainer container = plugin.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        container.getUUIDLists().write(0, List.of(uuid));

        plugin.getProtocolManager().sendServerPacket(player, container, false);

        if (sentPlayers.get(player) != null) {
            sentPlayers.get(player).remove(uuid);
        }
    }

    // 空のタブリストを送信する
    private void sendRemoveAllPacket(Player player) {
        final PacketContainer container = plugin.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        container.getUUIDLists().write(0, tabListEntries.keySet().stream().toList());

        plugin.getProtocolManager().sendServerPacket(player, container, false);
        if (sentPlayers.get(player) != null) {
            sentPlayers.get(player).clear();
        }
    }

    private void sendAddPacket(Player player, TabListEntry entry) {
        final PacketContainer playerInfoPacket = plugin.getProtocolManager().createPacket(PacketType.Play.Server.PLAYER_INFO);
        final EnumSet<EnumWrappers.PlayerInfoAction> actions = EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME
        );

        this.sentPlayers.computeIfAbsent(player, k -> new HashSet<>());
        this.sentPlayers.get(player).add(entry.getUuid());

        final WrappedRemoteChatSessionData chatSession = this.publicKeys.get(entry.getUuid());
        if (chatSession != null) {
            actions.add(EnumWrappers.PlayerInfoAction.INITIALIZE_CHAT);
        }
        playerInfoPacket.getPlayerInfoActions().write(0, actions);

        final WrappedGameProfile wrappedGameProfile = new WrappedGameProfile(entry.getUuid(), entry.getName());

        for (PlayerProperty p : entry.getProperties()) {
            WrappedSignedProperty v = WrappedSignedProperty.fromValues(p.getName(), p.getValue(), p.getSignature());
            wrappedGameProfile.getProperties().put(p.getName(), v);
        }

        playerInfoPacket.getPlayerInfoDataLists().write(1, List.of(new PlayerInfoData(
                entry.getUuid(),
                0,
                true,
                EnumWrappers.NativeGameMode.CREATIVE,
                wrappedGameProfile,
                WrappedChatComponent.fromText(entry.getName()),
                chatSession)
        ));

        plugin.getProtocolManager().sendServerPacket(player, playerInfoPacket, false);

        // 既に送信したことある場合は無視
        /*if (first) {
            final int entityId = Bukkit.getUnsafe().nextEntityId();

            final PacketContainer spawnEntityPacket = plugin.getProtocolManager().createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            spawnEntityPacket.getIntegers().write(0, entityId);
            spawnEntityPacket.getUUIDs().write(0, entry.getUuid());
            spawnEntityPacket.getEntityTypeModifier().write(0, EntityType.PLAYER);
            spawnEntityPacket.getDoubles().write(0, 0D); // x
            spawnEntityPacket.getDoubles().write(1, (double) Integer.MIN_VALUE); // y
            spawnEntityPacket.getDoubles().write(2, 0D); // z

            final PacketContainer entityMetadataPacket = plugin.getProtocolManager().createPacket(PacketType.Play.Server.ENTITY_METADATA);
            entityMetadataPacket.getIntegers().write(0, entityId);
            entityMetadataPacket.getDataValueCollectionModifier().write(0, List.of(new WrappedDataValue(
                    17, // https://wiki.vg/Entity_metadata#Player
                    WrappedDataWatcher.Registry.get(Byte.class),
                    Byte.MAX_VALUE)));

            plugin.getProtocolManager().sendServerPacket(player, spawnEntityPacket, false);
            plugin.getProtocolManager().sendServerPacket(player, entityMetadataPacket, false);
        }*/
    }
}
