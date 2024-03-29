package net.ME1312.SubServers.Sync;

import com.dosse.upnp.UPnP;
import com.google.gson.Gson;
import net.ME1312.SubData.Client.DataClient;
import net.ME1312.SubData.Client.Encryption.AES;
import net.ME1312.SubData.Client.Encryption.RSA;
import net.ME1312.SubData.Client.Library.DisconnectReason;
import net.ME1312.SubServers.Sync.Event.*;
import net.ME1312.Galaxi.Library.Config.YAMLConfig;
import net.ME1312.Galaxi.Library.Map.ObjectMap;
import net.ME1312.SubServers.Sync.Library.Compatibility.GalaxiCommand;
import net.ME1312.SubServers.Sync.Library.Compatibility.Logger;
import net.ME1312.SubServers.Sync.Library.Fallback.SmartReconnectHandler;
import net.ME1312.SubServers.Sync.Library.Metrics;
import net.ME1312.Galaxi.Library.NamedContainer;
import net.ME1312.Galaxi.Library.UniversalFile;
import net.ME1312.Galaxi.Library.Util;
import net.ME1312.Galaxi.Library.Version.Version;
import net.ME1312.SubData.Client.SubDataClient;
import net.ME1312.SubServers.Sync.Library.Updates.ConfigUpdater;
import net.ME1312.SubServers.Sync.Network.SubProtocol;
import net.ME1312.SubServers.Sync.Server.ServerImpl;
import net.ME1312.SubServers.Sync.Server.SubServerImpl;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Main Plugin Class
 */
public final class ExProxy extends BungeeCord implements Listener {
    HashMap<Integer, SubDataClient> subdata = new HashMap<Integer, SubDataClient>();
    NamedContainer<Long, Map<String, Map<String, String>>> lang = null;
    public final Map<String, ServerImpl> servers = new TreeMap<String, ServerImpl>();
    private final HashMap<UUID, List<ServerInfo>> fallbackLimbo = new HashMap<UUID, List<ServerInfo>>();

    public final PrintStream out;
    public final UniversalFile dir = new UniversalFile(new File(System.getProperty("user.dir")));
    public YAMLConfig config;
    public boolean redis = false;
    public final SubAPI api = new SubAPI(this);
    public SubProtocol subprotocol;
    public static final Version version = Version.fromString("2.14.4a");

    public final boolean isPatched;
    public final boolean isGalaxi;
    public long lastReload = -1;
    private long resetDate = 0;
    private boolean reconnect = false;
    private boolean posted = false;

    protected ExProxy(PrintStream out, boolean isPatched) throws Exception {
        this.isPatched = isPatched;
        this.isGalaxi = !Util.isException(() ->
                Util.reflect(Class.forName("net.ME1312.Galaxi.Engine.PluginManager").getMethod("findClasses", Class.class),
                        Util.reflect(Class.forName("net.ME1312.Galaxi.Engine.GalaxiEngine").getMethod("getPluginManager"),
                                Util.reflect(Class.forName("net.ME1312.Galaxi.Engine.GalaxiEngine").getMethod("getInstance"), null)), Launch.class));

        Util.reflect(Logger.class.getDeclaredField("plugin"), null, this);
        Logger.get("SubServers").info("Loading SubServers.Sync v" + version.toString() + " Libraries (for Minecraft " + api.getGameVersion()[api.getGameVersion().length - 1] + ")");

        this.out = out;
        if (!(new UniversalFile(dir, "config.yml").exists())) {
            Util.copyFromJar(ExProxy.class.getClassLoader(), "net/ME1312/SubServers/Sync/Library/Files/bungee.yml", new UniversalFile(dir, "config.yml").getPath());
            YAMLConfig tmp = new YAMLConfig(new UniversalFile("config.yml"));
            tmp.get().set("stats", UUID.randomUUID().toString());
            tmp.save();
            Logger.get("SubServers").info("Created ./config.yml");
        }
        UniversalFile dir = new UniversalFile(this.dir, "SubServers");
        dir.mkdir();

        ConfigUpdater.updateConfig(new UniversalFile(dir, "sync.yml"));
        config = new YAMLConfig(new UniversalFile(dir, "sync.yml"));

        subprotocol = SubProtocol.get();
        getPluginManager().registerListener(null, this);

        Logger.get("SubServers").info("Loading BungeeCord Libraries...");
    }

    /**
     * Load Hosts, Servers, SubServers, and SubData Direct
     */
    @Override
    public void startListeners() {
        try {
            resetDate = Calendar.getInstance().getTime().getTime();
            redis = getPluginManager().getPlugin("RedisBungee") != null;
            ConfigUpdater.updateConfig(new UniversalFile(dir, "SubServers:sync.yml"));
            config.reload();

            subprotocol.unregisterCipher("AES");
            subprotocol.unregisterCipher("AES-128");
            subprotocol.unregisterCipher("AES-192");
            subprotocol.unregisterCipher("AES-256");
            subprotocol.unregisterCipher("RSA");
            api.name = config.get().getMap("Settings").getMap("SubData").getString("Name", null);

            if (config.get().getMap("Settings").getMap("SubData").getRawString("Password", "").length() > 0) {
                subprotocol.registerCipher("AES", new AES(128, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));
                subprotocol.registerCipher("AES-128", new AES(128, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));
                subprotocol.registerCipher("AES-192", new AES(192, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));
                subprotocol.registerCipher("AES-256", new AES(256, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));

                Logger.get("SubData").info("AES Encryption Available");
            }
            if (new UniversalFile(dir, "SubServers:subdata.rsa.key").exists()) {
                try {
                    subprotocol.registerCipher("RSA", new RSA(new UniversalFile(dir, "SubServers:subdata.rsa.key")));
                    Logger.get("SubData").info("RSA Encryption Available");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            reconnect = true;
            Logger.get("SubData").info("");
            Logger.get("SubData").info("Connecting to /" + config.get().getMap("Settings").getMap("SubData").getRawString("Address", "127.0.0.1:4391"));
            connect(null);

            super.startListeners();

            if (UPnP.isUPnPAvailable()) {
                if (config.get().getMap("Settings").getMap("UPnP", new ObjectMap<String>()).getBoolean("Forward-Proxy", true)) for (ListenerInfo listener : getConfig().getListeners()) {
                    UPnP.openPortTCP(listener.getHost().getPort());
                }
            } else {
                getLogger().warning("UPnP is currently unavailable; Ports may not be automatically forwarded on this device");
            }

            if (!posted) {
                posted = true;
                post();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void connect(NamedContainer<DisconnectReason, DataClient> disconnect) throws IOException {
        int reconnect = config.get().getMap("Settings").getMap("SubData").getInt("Reconnect", 30);
        if (disconnect == null || (this.reconnect && reconnect > 0 && disconnect.name() != DisconnectReason.PROTOCOL_MISMATCH && disconnect.name() != DisconnectReason.ENCRYPTION_MISMATCH)) {
            long reset = resetDate;
            Timer timer = new Timer("SubServers.Sync::SubData_Reconnect_Handler");
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (reset == resetDate && (subdata.getOrDefault(0, null) == null || subdata.get(0).isClosed())) {
                            SubDataClient open = subprotocol.open((config.get().getMap("Settings").getMap("SubData").getRawString("Address", "127.0.0.1:4391").split(":")[0].equals("0.0.0.0"))?
                                            null:InetAddress.getByName(config.get().getMap("Settings").getMap("SubData").getRawString("Address", "127.0.0.1:4391").split(":")[0]),
                                    Integer.parseInt(config.get().getMap("Settings").getMap("SubData").getRawString("Address", "127.0.0.1:4391").split(":")[1]));

                            if (subdata.getOrDefault(0, null) != null) subdata.get(0).reconnect(open);
                            subdata.put(0, open);
                        }
                        timer.cancel();
                    } catch (IOException e) {
                        net.ME1312.SubServers.Sync.Library.Compatibility.Logger.get("SubData").info("Connection was unsuccessful, retrying in " + reconnect + " seconds");
                    }
                }
            }, (disconnect == null)?0:TimeUnit.SECONDS.toMillis(reconnect), TimeUnit.SECONDS.toMillis(reconnect));
        }
    }

    private void post() {
        if (config.get().getMap("Settings").getBoolean("Override-Bungee-Commands", true)) {
            getPluginManager().registerCommand(null, SubCommand.BungeeServer.newInstance(this, "server").get());
            getPluginManager().registerCommand(null, new SubCommand.BungeeList(this, "glist"));
        }
        getPluginManager().registerCommand(null, SubCommand.newInstance(this, "subservers").get());
        getPluginManager().registerCommand(null, SubCommand.newInstance(this, "subserver").get());
        getPluginManager().registerCommand(null, SubCommand.newInstance(this, "sub").get());
        GalaxiCommand.group(SubCommand.class);

        new Metrics(this);
        new Timer("SubServers.Sync::Routine_Update_Check").schedule(new TimerTask() {
            @SuppressWarnings("unchecked")
            @Override
            public void run() {
                try {
                    ObjectMap<String> tags = new ObjectMap<String>(new Gson().fromJson("{\"tags\":" + Util.readAll(new BufferedReader(new InputStreamReader(new URL("https://api.github.com/repos/ME1312/SubServers-2/git/refs/tags").openStream(), Charset.forName("UTF-8")))) + '}', Map.class));
                    List<Version> versions = new LinkedList<Version>();

                    Version updversion = version;
                    int updcount = 0;
                    for (ObjectMap<String> tag : tags.getMapList("tags")) versions.add(Version.fromString(tag.getString("ref").substring(10)));
                    Collections.sort(versions);
                    for (Version version : versions) {
                        if (version.compareTo(updversion) > 0) {
                            updversion = version;
                            updcount++;
                        }
                    }
                    if (updcount > 0) Logger.get("SubServers").info("SubServers.Sync v" + updversion + " is available. You are " + updcount + " version" + ((updcount == 1)?"":"s") + " behind.");
                } catch (Exception e) {}
            }
        }, 0, TimeUnit.DAYS.toMillis(2));
    }

    /**
     * Reference a RedisBungee method via reflection
     *
     * @param method Method to reference
     * @param args Method arguments
     * @return Method Response
     */
    @SuppressWarnings("unchecked")
    public Object redis(String method, NamedContainer<Class<?>, ?>... args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (redis) {
            Object api = getPluginManager().getPlugin("RedisBungee").getClass().getMethod("getApi").invoke(null);
            Class<?>[] classargs = new Class<?>[args.length];
            Object[] objargs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                classargs[i] = args[i].name();
                objargs[i] = args[i].get();
                if (!classargs[i].isInstance(objargs[i])) throw new ClassCastException(classargs[i].getCanonicalName() + " != " + objargs[i].getClass().getCanonicalName());
            }
            return api.getClass().getMethod(method, classargs).invoke(api, objargs);
        } else {
            throw new IllegalStateException("RedisBungee is not installed");
        }
    }

    /**
     * Further override BungeeCord's signature when patched into the same jar
     *
     * @return Software Name
     */
    @Override
    public String getName() {
        return (isPatched)?"SubServers Platform":super.getName();
    }

    /**
     * Get the name from BungeeCord's original signature (for determining which fork is being used)
     *
     * @return BungeeCord Software Name
     */
    public String getBungeeName() {
        return super.getName();
    }

    /**
     * Emulate BungeeCord's getServers()
     *
     * @return Server Map
     */
    @Override
    public Map<String, ServerInfo> getServers() {
        if (servers.size() > 0) {
            HashMap<String, ServerInfo> servers = new HashMap<String, ServerInfo>();
            for (ServerInfo server : this.servers.values()) servers.put(server.getName(), server);
            return servers;
        } else {
            return super.getServers();
        }
    }

    /**
     * Reset all changes made by startListeners
     *
     * @see ExProxy#startListeners()
     */
    @Override
    public void stopListeners() {
        try {
            Logger.get("SubServers").info("Resetting Server Data");
            servers.clear();

            reconnect = false;
            ArrayList<SubDataClient> tmp = new ArrayList<SubDataClient>();
            tmp.addAll(subdata.values());
            for (SubDataClient client : tmp) if (client != null) {
                client.close();
                Util.isException(client::waitFor);
            }
            subdata.clear();
            subdata.put(0, null);

            for (ListenerInfo listener : getConfig().getListeners()) {
                if (UPnP.isUPnPAvailable() && UPnP.isMappedTCP(listener.getHost().getPort())) UPnP.closePortTCP(listener.getHost().getPort());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        super.stopListeners();
    }

    @EventHandler(priority = Byte.MAX_VALUE)
    public void ping(ProxyPingEvent e) {
        int offline = 0;
        for (String name : e.getConnection().getListener().getServerPriority()) {
            ServerInfo server = getServerInfo(name);
            if (server == null || server instanceof SubServerImpl && !((SubServerImpl) server).isRunning()) offline++;
        }

        if (offline >= e.getConnection().getListener().getServerPriority().size()) {
            e.setResponse(new ServerPing(e.getResponse().getVersion(), e.getResponse().getPlayers(), new TextComponent(api.getLang("SubServers", "Bungee.Ping.Offline")), null));
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = Byte.MAX_VALUE)
    public void validate(ServerConnectEvent e) {
        Map<String, ServerInfo> servers = new TreeMap<String, ServerInfo>(this.servers);
        if (servers.keySet().contains(e.getTarget().getName().toLowerCase()) && e.getTarget() != servers.get(e.getTarget().getName().toLowerCase())) {
            e.setTarget(servers.get(e.getTarget().getName().toLowerCase()));
        } else {
            servers = getServers();
            if (servers.keySet().contains(e.getTarget().getName()) && e.getTarget() != servers.get(e.getTarget().getName())) {
                e.setTarget(servers.get(e.getTarget().getName()));
            }
        }

        if (!e.getTarget().canAccess(e.getPlayer())) {
            if (e.getPlayer().getServer() == null || fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId())) {
                if (!fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId()) || fallbackLimbo.get(e.getPlayer().getUniqueId()).contains(e.getTarget())) {
                    ServerKickEvent kick = new ServerKickEvent(e.getPlayer(), e.getTarget(), new BaseComponent[]{
                            new TextComponent(getTranslation("no_server_permission"))
                    }, null, ServerKickEvent.State.CONNECTING);
                    fallback(kick);
                    if (!kick.isCancelled()) e.getPlayer().disconnect(kick.getKickReasonComponent());
                    if (e.getPlayer().getServer() != null) e.setCancelled(true);
                }
            } else {
                e.getPlayer().sendMessage(getTranslation("no_server_permission"));
                e.setCancelled(true);
            }
        } else if (e.getPlayer().getServer() != null && !fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId()) && e.getTarget() instanceof SubServerImpl && !((SubServerImpl) e.getTarget()).isRunning()) {
            e.getPlayer().sendMessage(api.getLang("SubServers", "Bungee.Server.Offline"));
            e.setCancelled(true);
        }

        if (fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId())) {
            if (fallbackLimbo.get(e.getPlayer().getUniqueId()).contains(e.getTarget())) {
                fallbackLimbo.get(e.getPlayer().getUniqueId()).remove(e.getTarget());
            } else if (e.getPlayer().getServer() != null) {
                e.setCancelled(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = Byte.MAX_VALUE)
    public void fallback(ServerKickEvent e) {
        if (e.getPlayer() instanceof UserConnection && config.get().getMap("Settings").getBoolean("Smart-Fallback", true)) {
            Map<String, ServerInfo> fallbacks;
            if (!fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId())) {
                fallbacks = SmartReconnectHandler.getFallbackServers(e.getPlayer().getPendingConnection().getListener());
            } else {
                fallbacks = new LinkedHashMap<String, ServerInfo>();
                for (ServerInfo server : fallbackLimbo.get(e.getPlayer().getUniqueId())) fallbacks.put(server.getName(), server);
            }

            fallbacks.remove(e.getKickedFrom().getName());
            if (!fallbacks.isEmpty()) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(api.getLang("SubServers", "Bungee.Feature.Smart-Fallback").replace("$str$", (e.getKickedFrom() instanceof ServerImpl)?((ServerImpl) e.getKickedFrom()).getDisplayName():e.getKickedFrom().getName()).replace("$msg$", e.getKickReason()));
                if (!fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId())) fallbackLimbo.put(e.getPlayer().getUniqueId(), new LinkedList<>(fallbacks.values()));

                ServerInfo next = new LinkedList<Map.Entry<String, ServerInfo>>(fallbacks.entrySet()).getFirst().getValue();
                e.setCancelServer(next);
                if (Util.isException(() -> Util.reflect(ServerKickEvent.class.getDeclaredMethod("setCancelServers", ServerInfo[].class), e, (Object) fallbacks.values().toArray(new ServerInfo[0])))) {
                    ((UserConnection) e.getPlayer()).setServerJoinQueue(new LinkedList<>(fallbacks.keySet()));
                    ((UserConnection) e.getPlayer()).connect(next, null, true);
                }
            }
        }
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority = Byte.MAX_VALUE)
    public void fallbackFound(ServerConnectedEvent e) {
        if (fallbackLimbo.keySet().contains(e.getPlayer().getUniqueId())) new Timer("SubServers.Sync::Fallback_Limbo_Timer(" + e.getPlayer().getUniqueId() + ')').schedule(new TimerTask() {
            @Override
            public void run() {
                if (e.getPlayer().getServer() != null && !((UserConnection) e.getPlayer()).isDimensionChange() && e.getPlayer().getServer().getInfo().getAddress().equals(e.getServer().getInfo().getAddress())) {
                    fallbackLimbo.remove(e.getPlayer().getUniqueId());
                    e.getPlayer().sendMessage(api.getLang("SubServers", "Bungee.Feature.Smart-Fallback.Result").replace("$str$", (e.getServer().getInfo() instanceof ServerImpl)?((ServerImpl) e.getServer().getInfo()).getDisplayName():e.getServer().getInfo().getName()));
                }
            }
        }, 1000);
    }
    @EventHandler(priority = Byte.MIN_VALUE)
    public void resetLimbo(PlayerDisconnectEvent e) {
        fallbackLimbo.remove(e.getPlayer().getUniqueId());
        SubCommand.players.remove(e.getPlayer().getUniqueId());
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, UUID> getSubDataAsMap(net.ME1312.SubServers.Sync.Network.API.Server server) {
        HashMap<Integer, UUID> map = new HashMap<Integer, UUID>();
        ObjectMap<Integer> subdata = new ObjectMap<Integer>((Map<Integer, ?>) server.getRaw().getObject("subdata"));
        for (Integer channel : subdata.getKeys()) map.put(channel, subdata.getUUID(channel));
        return map;
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    public void add(SubAddServerEvent e) {
        api.getServer(e.getServer(), server -> {
            if (server != null) {
                if (server instanceof net.ME1312.SubServers.Sync.Network.API.SubServer) {
                    servers.put(server.getName().toLowerCase(), new SubServerImpl(server.getSignature(), server.getName(), server.getDisplayName(), server.getAddress(),
                            getSubDataAsMap(server), server.getMotd(), server.isHidden(), server.isRestricted(), server.getWhitelist(), ((net.ME1312.SubServers.Sync.Network.API.SubServer) server).isRunning()));
                    Logger.get("SubServers").info("Added SubServer: " + e.getServer());
                } else {
                    servers.put(server.getName().toLowerCase(), new ServerImpl(server.getSignature(), server.getName(), server.getDisplayName(), server.getAddress(),
                            getSubDataAsMap(server), server.getMotd(), server.isHidden(), server.isRestricted(), server.getWhitelist()));
                    Logger.get("SubServers").info("Added Server: " + e.getServer());
                }
            } else System.out.println("PacketDownloadServerInfo(" + e.getServer() + ") returned with an invalid response");
        });
    }

    public Boolean merge(net.ME1312.SubServers.Sync.Network.API.Server server) {
        ServerImpl current = servers.get(server.getName().toLowerCase());
        if (current == null || server instanceof net.ME1312.SubServers.Sync.Network.API.SubServer || !(current instanceof SubServerImpl)) {
            if (current == null || !current.getSignature().equals(server.getSignature())) {
                if (server instanceof net.ME1312.SubServers.Sync.Network.API.SubServer) {
                    servers.put(server.getName().toLowerCase(), new SubServerImpl(server.getSignature(), server.getName(), server.getDisplayName(), server.getAddress(),
                            getSubDataAsMap(server), server.getMotd(), server.isHidden(), server.isRestricted(), server.getWhitelist(), ((net.ME1312.SubServers.Sync.Network.API.SubServer) server).isRunning()));
                } else {
                    servers.put(server.getName().toLowerCase(), new ServerImpl(server.getSignature(), server.getName(), server.getDisplayName(), server.getAddress(),
                            getSubDataAsMap(server), server.getMotd(), server.isHidden(), server.isRestricted(), server.getWhitelist()));
                }

                Logger.get("SubServers").info("Added "+((server instanceof net.ME1312.SubServers.Sync.Network.API.SubServer)?"Sub":"")+"Server: " + server.getName());
                return true;
            } else {
                if (server instanceof net.ME1312.SubServers.Sync.Network.API.SubServer) {
                    if (((net.ME1312.SubServers.Sync.Network.API.SubServer) server).isRunning() != ((SubServerImpl) current).isRunning())
                        ((SubServerImpl) current).setRunning(((net.ME1312.SubServers.Sync.Network.API.SubServer) server).isRunning());
                }
                if (!server.getMotd().equals(current.getMotd()))
                    current.setMotd(server.getMotd());
                if (server.isHidden() != current.isHidden())
                    current.setHidden(server.isHidden());
                if (server.isRestricted() != current.isRestricted())
                    current.setRestricted(server.isRestricted());
                if (!server.getDisplayName().equals(current.getDisplayName()))
                    current.setDisplayName(server.getDisplayName());

                Logger.get("SubServers").info("Re-added "+((server instanceof net.ME1312.SubServers.Sync.Network.API.SubServer)?"Sub":"")+"Server: " + server.getName());
                return false;
            }
        }
        return null;
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    public void edit(SubEditServerEvent e) {
        if (servers.keySet().contains(e.getServer().toLowerCase())) {
            ServerImpl server = servers.get(e.getServer().toLowerCase());
            switch (e.getEdit().name().toLowerCase()) {
                case "display":
                    server.setDisplayName(e.getEdit().get().asString());
                    break;
                case "motd":
                    server.setMotd(ChatColor.translateAlternateColorCodes('&', e.getEdit().get().asString()));
                    break;
                case "restricted":
                    server.setRestricted(e.getEdit().get().asBoolean());
                    break;
                case "hidden":
                    server.setHidden(e.getEdit().get().asBoolean());
                    break;
            }
        }
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    public void start(SubStartEvent e) {
        if (servers.keySet().contains(e.getServer().toLowerCase()) && servers.get(e.getServer().toLowerCase()) instanceof SubServerImpl)
            ((SubServerImpl) servers.get(e.getServer().toLowerCase())).setRunning(true);
    }

    public void connect(ServerImpl server, int channel, UUID address) {
        if (server != null) {
            server.setSubData(address, channel);
        }
    }

    public void disconnect(ServerImpl server, int channel) {
        if (server != null) {
            server.setSubData(null, channel);
        }
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    public void stop(SubStoppedEvent e) {
        if (servers.keySet().contains(e.getServer().toLowerCase()) && servers.get(e.getServer().toLowerCase()) instanceof SubServerImpl)
            ((SubServerImpl) servers.get(e.getServer().toLowerCase())).setRunning(false);
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    public void remove(SubRemoveServerEvent e) {
        if (servers.keySet().contains(e.getServer().toLowerCase()))
            servers.remove(e.getServer().toLowerCase());
            Logger.get("SubServers").info("Removed Server: " + e.getServer());
    }
}
