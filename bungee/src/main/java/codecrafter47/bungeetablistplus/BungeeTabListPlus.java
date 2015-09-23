/*
 * BungeeTabListPlus - a BungeeCord plugin to customize the tablist
 *
 * Copyright (C) 2014 - 2015 Florian Stober
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package codecrafter47.bungeetablistplus;

import codecrafter47.bungeetablistplus.bridge.BukkitBridge;
import codecrafter47.bungeetablistplus.commands.OldSuperCommand;
import codecrafter47.bungeetablistplus.commands.SuperCommand;
import codecrafter47.bungeetablistplus.common.BugReportingService;
import codecrafter47.bungeetablistplus.common.Constants;
import codecrafter47.bungeetablistplus.listener.TabListListener;
import codecrafter47.bungeetablistplus.managers.*;
import codecrafter47.bungeetablistplus.packet.*;
import codecrafter47.bungeetablistplus.placeholder.*;
import codecrafter47.bungeetablistplus.player.*;
import codecrafter47.bungeetablistplus.updater.UpdateChecker;
import codecrafter47.bungeetablistplus.updater.UpdateNotifier;
import codecrafter47.bungeetablistplus.version.BungeeProtocolVersionProvider;
import codecrafter47.bungeetablistplus.version.ProtocolSupportVersionProvider;
import codecrafter47.bungeetablistplus.version.ProtocolVersionProvider;
import codecrafter47.data.Values;
import codecrafter47.util.bungee.PingTask;
import lombok.Getter;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.Team;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Main Class of BungeeTabListPlus
 *
 * @author Florian Stober
 */
public class BungeeTabListPlus {

    /**
     * Holds an INSTANCE of itself if the plugin is enabled
     */
    private static BungeeTabListPlus INSTANCE;
    @Getter
    private final Plugin plugin;
    private Collection<IPlayerProvider> playerProviders;

    public BungeeTabListPlus(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Static getter for the current instance of the plugin
     *
     * @return the current instance of the plugin, null if the plugin is
     * disabled
     */
    public static BungeeTabListPlus getInstance(Plugin plugin) {
        if (INSTANCE == null) {
            INSTANCE = new BungeeTabListPlus(plugin);
        }
        return INSTANCE;
    }

    public static BungeeTabListPlus getInstance() {
        return INSTANCE;
    }

    /**
     * provides access to the configuration
     */
    private ConfigManager config;

    private FakePlayerManager fakePlayerManager;

    /**
     * provides access to the Placeholder Manager use this to add Placeholders
     */
    private PlaceholderManager placeholderManager;

    private PermissionManager pm;

    private TabListManager tabLists;
    private final TabListListener listener = new TabListListener(this);

    private final SendingQueue resendQueue = new SendingQueue();

    private ScheduledTask refreshThread = null;

    private final static Collection<String> hiddenPlayers = new HashSet<>();

    private BukkitBridge bukkitBridge;

    private UpdateChecker updateChecker = null;

    static private boolean is18 = true;

    static private boolean isAbove995 = false;

    private LegacyPacketAccess legacyPacketAccess;

    private PacketAccess packetAccess;

    private final Map<String, PingTask> serverState = new HashMap<>();

    private SkinManager skins;

    @Getter
    private BungeePlayerProvider bungeePlayerProvider = new BungeePlayerProvider();

    public PingTask getServerState(String o) {
        return serverState.get(o);
    }

    @Getter
    private ProtocolVersionProvider protocolVersionProvider;

    /**
     * Called when the plugin is enabled
     */
    public void onEnable() {
        INSTANCE = this;
        try {
            config = new ConfigManager(plugin);
        } catch (IOException ex) {
            plugin.getLogger().warning("Unable to load Config");
            plugin.getLogger().log(Level.WARNING, null, ex);
            plugin.getLogger().warning("Disabling Plugin");
            return;
        }

        if (config.getMainConfig().automaticallySendBugReports) {
            BugReportingService bugReportingService = new BugReportingService(Level.SEVERE, getPlugin().getDescription().getName(), getPlugin().getDescription().getVersion(), command -> plugin.getProxy().getScheduler().runAsync(plugin, command));
            bugReportingService.registerLogger(getLogger());
        }

        try {
            Class.forName("net.md_5.bungee.tab.TabList");
        } catch (ClassNotFoundException ex) {
            is18 = false;
        }

        try {
            Class.forName("net.md_5.bungee.api.Title");
            isAbove995 = true;
        } catch (ClassNotFoundException ex) {
            isAbove995 = false;
        }

        legacyPacketAccess = new LegacyPacketAccessImpl();

        if (!legacyPacketAccess.isTabModificationSupported()) {
            plugin.getLogger().warning("Your BungeeCord Version isn't supported yet");
            plugin.getLogger().warning("Disabling Plugin");
            return;
        }

        if ((!legacyPacketAccess.isScoreboardSupported()) && config.getMainConfig().useScoreboardToBypass16CharLimit) {
            plugin.getLogger().warning("Your BungeeCord Version does not support the following option: 'useScoreboardToBypass16CharLimit'");
            plugin.getLogger().warning("This option will be disabled");
            config.getMainConfig().useScoreboardToBypass16CharLimit = false;
        }

        if (isVersion18()) {
            packetAccess = new PacketAccessImpl(getLogger());
            if (!packetAccess.isTabHeaderFooterSupported()) {
                plugin.getLogger().warning("Your BungeeCord version doesn't support tablist header and footer modification");
            }

            skins = new SkinManager(plugin);
        }

        // start server ping tasks
        if (config.getMainConfig().pingDelay > 0) {
            for (ServerInfo server : plugin.getProxy().getServers().values()) {
                PingTask task = new PingTask(server);
                serverState.put(server.getName(), task);
                plugin.getProxy().getScheduler().schedule(plugin, task, config.
                                getMainConfig().pingDelay,
                        config.getMainConfig().pingDelay, TimeUnit.SECONDS);
            }
        }

        fakePlayerManager = new FakePlayerManager(plugin);

        playerProviders = new ArrayList<>();

        playerProviders.add(fakePlayerManager);

        if (plugin.getProxy().getPluginManager().getPlugin("RedisBungee") != null) {
            playerProviders.add(new RedisPlayerProvider());
            plugin.getLogger().info("Hooked RedisBungee");
        } else {
            playerProviders.add(bungeePlayerProvider);
        }

        plugin.getProxy().registerChannel(Constants.channel);
        bukkitBridge = new BukkitBridge(this);

        pm = new PermissionManager(this);
        placeholderManager = new PlaceholderManager();
        placeholderManager.registerPlaceholderProvider(new BasicPlaveholders());
        placeholderManager.registerPlaceholderProvider(new BukkitPlaceholders());
        placeholderManager.registerPlaceholderProvider(new ColorPlaceholder());
        placeholderManager.registerPlaceholderProvider(new ConditionalPlaceholders());
        placeholderManager.registerPlaceholderProvider(new OnlineStatePlaceholder());
        placeholderManager.registerPlaceholderProvider(new PlayerCountPlaceholder());
        if (plugin.getProxy().getPluginManager().getPlugin("RedisBungee") != null) {
            placeholderManager.registerPlaceholderProvider(new RedisBungeePlaceholders());
        }
        placeholderManager.registerPlaceholderProvider(new TimePlaceholders());

        tabLists = new TabListManager(this);
        if (!tabLists.loadTabLists()) {
            return;
        }

        if (plugin.getProxy().getPluginManager().getPlugin("ProtocolSupportBungee") != null) {
            protocolVersionProvider = new ProtocolSupportVersionProvider();
        } else {
            protocolVersionProvider = new BungeeProtocolVersionProvider();
        }

        ProxyServer.getInstance().getPluginManager().registerListener(plugin,
                listener);

        ResendThread resendThread = new ResendThread(resendQueue,
                config.getMainConfig().tablistUpdateInterval);
        plugin.getProxy().getScheduler().schedule(plugin, resendThread, 1,
                TimeUnit.SECONDS);
        startRefreshThread();

        // register commands and update Notifier
        try {
            Thread.currentThread().getContextClassLoader().loadClass(
                    "net.md_5.bungee.api.chat.ComponentBuilder");
            ProxyServer.getInstance().getPluginManager().registerCommand(
                    plugin,
                    new SuperCommand(this));
            ProxyServer.getInstance().getScheduler().schedule(plugin,
                    new UpdateNotifier(this), 15, 15, TimeUnit.MINUTES);
        } catch (Exception ex) {
            ProxyServer.getInstance().getPluginManager().registerCommand(
                    plugin,
                    new OldSuperCommand(this));
        }

        // Start metrics
        try {
            Metrics metrics = new Metrics(plugin);
            metrics.start();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Metrics", e);
        }

        // Load updateCheck thread
        if (config.getMainConfig().checkForUpdates) {
            updateChecker = new UpdateChecker(plugin);
        }

        if (isVersion18()) {
            try {
                List<Integer> supportedProtocolVersions = Arrays.stream(ProtocolConstants.class.getDeclaredFields()).filter(f -> (f.getModifiers() & 8) != 0).filter(f -> f.getType() == int.class).map(f -> {
                    try {
                        f.setAccessible(true);
                        return f.getInt(null);
                    } catch (IllegalAccessException e) {
                        reportError(e);
                        return 0;
                    }
                }).collect(Collectors.toList());
                // register team packet
                int maxProtocolVersion = supportedProtocolVersions.stream().mapToInt(Integer::intValue).max().getAsInt();
                if (maxProtocolVersion > 47) {
                    // 1.9
                    Class clazz = Protocol.DirectionData.class;
                    Method registerPacket = clazz.getDeclaredMethod("registerPacket", int.class, int.class, Class.class);
                    Method getId = clazz.getDeclaredMethod("getId", Class.class, int.class);
                    getId.setAccessible(true);
                    registerPacket.setAccessible(true);
                    registerPacket.invoke(Protocol.GAME.TO_CLIENT, 62, getId.invoke(Protocol.GAME.TO_CLIENT, Team.class, maxProtocolVersion), TeamPacket.class);
                } else {
                    // 1.8
                    Class clazz = Protocol.DirectionData.class;
                    Method registerPacket = clazz.getDeclaredMethod("registerPacket", int.class, Class.class);
                    registerPacket.setAccessible(true);
                    registerPacket.invoke(Protocol.GAME.TO_CLIENT, 62, TeamPacket.class);
                }
            } catch (IllegalArgumentException | NoSuchMethodException | SecurityException | InvocationTargetException | IllegalAccessException ex) {
                getLogger().log(Level.SEVERE, "Failed to hook team packet", ex);
            }
        }
    }

    private void startRefreshThread() {
        if (config.getMainConfig().tablistUpdateInterval > 0) {
            try {
                refreshThread = ProxyServer.getInstance().getScheduler().
                        schedule(
                                plugin, new Runnable() {

                                    @Override
                                    public void run() {
                                        resendTabLists();
                                        startRefreshThread();
                                    }
                                },
                                (long) (config.getMainConfig().tablistUpdateInterval * 1000),
                                TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ex) {
                // this occurs on proxy shutdown -> we can safely ignore it
            }
        } else {
            refreshThread = null;
        }
    }

    /**
     * Reloads most settings of the plugin
     */
    public boolean reload() {
        try {
            config = new ConfigManager(plugin);
            placeholderManager.reload();
            TabListManager tabListManager = new TabListManager(this);
            if (!tabListManager.loadTabLists()) {
                return false;
            }
            tabLists = tabListManager;
            fakePlayerManager.reload();
            resendTabLists();
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Unable to reload Config", ex);
        }
        if (refreshThread == null) {
            startRefreshThread();
        }
        return true;
    }

    /**
     * updates the tabList on all connected clients
     */
    public void resendTabLists() {
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            resendQueue.addPlayer(player);
        }
    }

    /**
     * updates the tablist for one player; the player is put at top of the
     * resend-queue
     *
     * @param player the player whos tablist should be updated
     */
    public void sendImmediate(ProxiedPlayer player) {
        resendQueue.addFrontPlayer(player);
    }

    /**
     * updates the tablist for one player; the player is put at the end of the
     * resend-queue
     *
     * @param player the player whos tablist should be updated
     */
    public void sendLater(ProxiedPlayer player) {
        resendQueue.addPlayer(player);
    }

    /**
     * Getter for an instance of the PlayerManager. For internal use only.
     *
     * @return an instance of the PlayerManager or null
     */
    public PlayerManager constructPlayerManager() {
        return new PlayerManager(this, playerProviders);
    }

    public SkinManager getSkinManager() {
        return skins;
    }

    public LegacyPacketAccess getLegacyPacketAccess() {
        return legacyPacketAccess;
    }

    public PacketAccess getPacketAccess() {
        return packetAccess;
    }

    /**
     * Getter for the PermissionManager. For internal use only.
     *
     * @return an instance of the PermissionManager or null
     */
    public PermissionManager getPermissionManager() {
        return pm;
    }

    /**
     * Getter for the ConfigManager. For internal use only.
     *
     * @return an instance of the ConfigManager or null
     */
    public ConfigManager getConfigManager() {
        return config;
    }

    public PlaceholderManager getPlaceholderManager() {
        return placeholderManager;
    }

    /**
     * Getter for the TabListManager. For internal use only
     *
     * @return an instance of the TabListManager
     */
    public TabListManager getTabListManager() {
        return tabLists;
    }

    /**
     * checks whether a player is hidden from the tablist
     *
     * @param player the player object for which the check should be performed
     * @return true if the player is hidden, false otherwise
     */
    public static boolean isHidden(IPlayer player, ProxiedPlayer viewer) {
        if (getInstance().getPermissionManager().hasPermission(viewer, "bungeetablistplus.seevanished")) return false;
        return isHidden(player) || isHiddenServer(player.getServer().orElse(null));
    }

    /**
     * checks whether a player is hidden from the tablist
     *
     * @param player the player object for which the check should be performed
     * @return true if the player is hidden, false otherwise
     */
    public static boolean isHidden(IPlayer player) {
        final boolean[] hidden = new boolean[1];
        synchronized (hiddenPlayers) {
            String name = player.getName();
            hidden[0] = hiddenPlayers.contains(name);
        }
        BukkitBridge bukkitBridge = getInstance().bukkitBridge;
        bukkitBridge.getPlayerInformation(player, Values.Player.VanishNoPacket.IsVanished).ifPresent(b -> hidden[0] |= b);
        bukkitBridge.getPlayerInformation(player, Values.Player.SuperVanish.IsVanished).ifPresent(b -> hidden[0] |= b);
        bukkitBridge.getPlayerInformation(player, Values.Player.Essentials.IsVanished).ifPresent(b -> hidden[0] |= b);
        return hidden[0];
    }

    /**
     * Hides a player from the tablist
     *
     * @param player The player which should be hidden.
     */
    public static void hidePlayer(ProxiedPlayer player) {
        synchronized (hiddenPlayers) {
            String name = player.getName();
            if (!hiddenPlayers.contains(name))
                hiddenPlayers.add(name);
        }
    }

    /**
     * Unhides a previously hidden player from the tablist. Only works if the
     * playe has been hidden via the hidePlayer method. Not works for players
     * hidden by VanishNoPacket
     *
     * @param player the player on which the operation should be performed
     */
    public static void unhidePlayer(ProxiedPlayer player) {
        synchronized (hiddenPlayers) {
            String name = player.getName();
            hiddenPlayers.remove(name);
        }
    }

    public static boolean isHiddenServer(ServerInfo server) {
        if (server == null)
            return false;
        return getInstance().config.getMainConfig().hiddenServers.contains(server.getName());
    }

    /**
     * Getter for BukkitBridge. For internal use only.
     *
     * @return an instance of BukkitBridge
     */
    public BukkitBridge getBridge() {
        return this.bukkitBridge;
    }

    /**
     * Checks whether an update for BungeeTabListPlus is available. Acctually
     * the check is performed in a background task and this only returns the
     * result.
     *
     * @return true if an newer version of BungeeTabListPlus is available
     */
    public boolean isUpdateAvailable() {
        return updateChecker != null && updateChecker.isUpdateAvailable();
    }

    public void reportError(Throwable th) {
        plugin.getLogger().log(Level.SEVERE,
                ChatColor.RED + "An internal error occurred! Please send the "
                        + "following StackTrace to the developer in order to help"
                        + " resolving the problem",
                th);
    }

    public static boolean isVersion18() {
        return is18;
    }

    public static Object getTabList(ProxiedPlayer player) throws
            IllegalArgumentException, IllegalAccessException,
            NoSuchFieldException {
        Class cplayer = UserConnection.class;
        Field tabListHandler = cplayer.getDeclaredField(
                isVersion18() ? "tabListHandler" : "tabList");
        tabListHandler.setAccessible(true);
        return tabListHandler.get(player);
    }

    public static void setTabList(ProxiedPlayer player, Object tabList) throws
            IllegalArgumentException, IllegalAccessException,
            NoSuchFieldException {
        Class cplayer = UserConnection.class;
        Field tabListHandler = cplayer.getDeclaredField(
                isVersion18() ? "tabListHandler" : "tabList");
        tabListHandler.setAccessible(true);
        tabListHandler.set(player, tabList);
    }

    public static boolean isAbove995() {
        return isAbove995;
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public ProxyServer getProxy() {
        return plugin.getProxy();
    }

    public boolean isServer(String s) {
        for (ServerInfo server : ProxyServer.getInstance().getServers().values()) {
            if (s.equalsIgnoreCase(server.getName())) {
                return true;
            }
            int i = s.indexOf('#');
            if (i > 1) {
                if (s.substring(0, i).equalsIgnoreCase(server.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
