package com.arcanevale.endrescue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вытаскивает игроков из энда.
 *
 * Два разных случая, и они решаются по-разному:
 *
 * 1) Энд выключен (allow-end: false). Мир не загружен, попасть в него нельзя,
 *    но у части игроков в playerdata осталось Dimension: minecraft:the_end.
 *    Сервер при входе переносит их в обычный мир, СОХРАНЯЯ координаты энда -
 *    человек появляется в случайном месте, нередко внутри блоков или в воздухе.
 *    Ловим это чтением файла до логина и телепортируем сразу после входа.
 *
 * 2) Энд включён. Тогда просто не пускаем: вход в мир и портал ведут на спавн.
 */
public final class EndRescue extends JavaPlugin implements Listener {

    private final Set<UUID> rescueOnJoin = ConcurrentHashMap.newKeySet();
    private final MiniMessage mm = MiniMessage.miniMessage();

    private boolean checkOfflineData;
    private boolean blockPortals;
    private boolean preferBed;
    private String messageRaw;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Включён. Проверка playerdata: " + checkOfflineData + ", блокировка порталов: " + blockPortals);
    }

    private void reloadSettings() {
        reloadConfig();
        checkOfflineData = getConfig().getBoolean("check-offline-data", true);
        blockPortals = getConfig().getBoolean("block-portals", true);
        preferBed = getConfig().getBoolean("prefer-bed", true);
        messageRaw = getConfig().getString("message",
                "<gradient:#9b6bff:#ff2df1>Энд закрыт.</gradient> <gray>Мы вернули тебя на спавн.</gray>");
    }

    // ---------- случай 1: энд выключен, смотрим файл до логина ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!checkOfflineData || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (main == null) {
            return;
        }
        File playerData = new File(main.getWorldFolder(), "playerdata");
        String dim = DimensionPeek.read(playerData, e.getUniqueId().toString());
        if (dim != null && dim.endsWith("the_end")) {
            rescueOnJoin.add(e.getUniqueId());
            getLogger().info(e.getName() + " сохранён в энде - вытащим после входа");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean fromFile = rescueOnJoin.remove(p.getUniqueId());
        boolean inEnd = p.getWorld().getEnvironment() == World.Environment.THE_END;
        if (fromFile || inEnd) {
            rescue(p, fromFile ? "данные игрока указывали на энд" : "вошёл в энде");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        rescueOnJoin.remove(e.getPlayer().getUniqueId());
    }

    // ---------- случай 2: энд включён, не пускаем ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (e.getPlayer().getWorld().getEnvironment() == World.Environment.THE_END) {
            rescue(e.getPlayer(), "перешёл в энд");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!blockPortals) {
            return;
        }
        Location to = e.getTo();
        if (to != null && to.getWorld() != null
                && to.getWorld().getEnvironment() == World.Environment.THE_END) {
            e.setCancelled(true);
            send(e.getPlayer());
        }
    }

    // ---------- сам перенос ----------

    private void rescue(Player p, String reason) {
        if (p.hasPermission("endrescue.bypass")) {
            getLogger().info(p.getName() + " пропущен: есть endrescue.bypass");
            return;
        }
        Location target = destinationFor(p);
        // teleportAsync, а не teleport - на Folia синхронный телепорт кинет исключение
        p.teleportAsync(target).thenAccept(ok -> {
            if (ok) {
                send(p);
                getLogger().info(p.getName() + " перенесён на " + fmt(target) + " (" + reason + ")");
            } else {
                getLogger().warning(p.getName() + ": телепорт не удался (" + reason + ")");
            }
        });
    }

    private Location destinationFor(Player p) {
        if (preferBed) {
            Location bed = p.getRespawnLocation();
            if (bed != null && bed.getWorld() != null
                    && bed.getWorld().getEnvironment() == World.Environment.NORMAL) {
                return bed;
            }
        }
        if (getConfig().getBoolean("custom-destination.enabled", false)) {
            World w = Bukkit.getWorld(getConfig().getString("custom-destination.world", "world"));
            if (w != null) {
                return new Location(w,
                        getConfig().getDouble("custom-destination.x"),
                        getConfig().getDouble("custom-destination.y"),
                        getConfig().getDouble("custom-destination.z"));
            }
        }
        World overworld = Bukkit.getWorlds().get(0);
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) {
                overworld = w;
                break;
            }
        }
        return overworld.getSpawnLocation();
    }

    private void send(Player p) {
        if (messageRaw != null && !messageRaw.isBlank()) {
            Component c = mm.deserialize(messageRaw);
            p.sendMessage(c);
        }
    }

    private static String fmt(Location l) {
        return l.getWorld().getName() + " " + Math.round(l.getX()) + " " + Math.round(l.getY()) + " " + Math.round(l.getZ());
    }
}
