package net.ME1312.SubServers.Client.Sponge;

import com.google.gson.Gson;
import com.google.inject.Inject;
import net.ME1312.SubData.Client.DataClient;
import net.ME1312.SubData.Client.Encryption.AES;
import net.ME1312.SubData.Client.Encryption.RSA;
import net.ME1312.SubData.Client.Library.DisconnectReason;
import net.ME1312.SubData.Client.SubDataClient;
import net.ME1312.SubServers.Client.Sponge.Graphic.UIHandler;
import net.ME1312.Galaxi.Library.Config.YAMLConfig;
import net.ME1312.Galaxi.Library.Map.ObjectMap;
import net.ME1312.SubServers.Client.Sponge.Library.Metrics;
import net.ME1312.Galaxi.Library.NamedContainer;
import net.ME1312.Galaxi.Library.UniversalFile;
import net.ME1312.Galaxi.Library.Util;
import net.ME1312.Galaxi.Library.Version.Version;
import net.ME1312.SubServers.Client.Sponge.Library.Updates.ConfigUpdater;
import net.ME1312.SubServers.Client.Sponge.Network.SubProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.api.Game;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SubServers Client Plugin Class
 */
@Plugin(id = "subservers-client-sponge", name = "SubServers-Client-Sponge", authors = "ME1312", version = "2.14.4a", url = "https://github.com/ME1312/SubServers-2", description = "Take control of the server manager — from your servers")
public final class SubPlugin {
    HashMap<Integer, SubDataClient> subdata = new HashMap<Integer, SubDataClient>();
    NamedContainer<Long, Map<String, Map<String, String>>> lang = null;
    public YAMLConfig config;
    public SubProtocol subprotocol;

    @ConfigDir(sharedRoot = false)
    @Inject public File dir;
    public Logger log = LoggerFactory.getLogger("SubServers");
    public UIHandler gui = null;
    public Version version;
    public SubAPI api;
    @Inject public PluginContainer plugin;
    @Inject public Game game;

    private boolean running = false;
    private long resetDate = 0;
    private boolean reconnect = false;

    @Listener
    public void setup(GamePreInitializationEvent event) {
        if (plugin.getVersion().isPresent()) {
            version = Version.fromString(plugin.getVersion().get());
        } else version = new Version("Custom");
        subdata.put(0, null);
    }

    /**
     * Enable Plugin
     */
    @Listener
    @SuppressWarnings("unchecked")
    public void enable(GameInitializationEvent event) {
        api = new SubAPI(this);
        try {
            log.info("Loading SubServers.Client.Sponge v" + version.toString() + " Libraries (for Minecraft " + api.getGameVersion() + ")");
            dir.mkdirs();
            if (new UniversalFile(dir.getParentFile(), "SubServers-Client:config.yml").exists()) {
                Files.move(new UniversalFile(dir.getParentFile(), "SubServers-Client:config.yml").toPath(), new UniversalFile(dir, "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                Util.deleteDirectory(new UniversalFile(dir.getParentFile(), "SubServers-Client"));
            }
            ConfigUpdater.updateConfig(new UniversalFile(dir, "config.yml"));
            config = new YAMLConfig(new UniversalFile(dir, "config.yml"));
            if (new UniversalFile(new File(System.getProperty("user.dir")), "subdata.json").exists()) {
                FileReader reader = new FileReader(new UniversalFile(new File(System.getProperty("user.dir")), "subdata.json"));
                config.get().getMap("Settings").set("SubData", new ObjectMap<String>(new Gson().fromJson(Util.readAll(reader), Map.class)));
                config.save();
                reader.close();
                new UniversalFile(new File(System.getProperty("user.dir")), "subdata.json").delete();
            }
            if (new UniversalFile(new File(System.getProperty("user.dir")), "subdata.rsa.key").exists()) {
                if (new UniversalFile(dir, "subdata.rsa.key").exists()) new UniversalFile(dir, "subdata.rsa.key").delete();
                Files.move(new UniversalFile(new File(System.getProperty("user.dir")), "subdata.rsa.key").toPath(), new UniversalFile(dir, "subdata.rsa.key").toPath());
            }

            running = true;
            subprotocol = SubProtocol.get();
            reload(false);

            if (!config.get().getMap("Settings").getBoolean("API-Only-Mode", false)) {
                //gui = new InternalUIHandler(this);
                Sponge.getCommandManager().register(plugin, new SubCommand(this).spec(), "sub", "subserver", "subservers");
            }

            new Metrics(this);
            game.getScheduler().createTaskBuilder().async().execute(() -> {
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
                    if (updcount > 0) log.info("SubServers.Client.Sponge v" + updversion + " is available. You are " + updcount + " version" + ((updcount == 1)?"":"s") + " behind.");
                } catch (Exception e) {}
            }).delay(0, TimeUnit.MILLISECONDS).interval(2, TimeUnit.DAYS).submit(plugin);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    public void reload(boolean notifyPlugins) throws IOException {
        reconnect = false;
        resetDate = Calendar.getInstance().getTime().getTime();
        ArrayList<SubDataClient> tmp = new ArrayList<SubDataClient>();
        tmp.addAll(subdata.values());
        for (SubDataClient client : tmp) if (client != null) {
            client.close();
            Util.isException(client::waitFor);
        }
        subdata.clear();
        subdata.put(0, null);

        ConfigUpdater.updateConfig(new UniversalFile(dir, "config.yml"));
        config.reload();

        subprotocol.unregisterCipher("AES");
        subprotocol.unregisterCipher("AES-128");
        subprotocol.unregisterCipher("AES-192");
        subprotocol.unregisterCipher("AES-256");
        subprotocol.unregisterCipher("RSA");
        api.name = config.get().getMap("Settings").getMap("SubData").getString("Name", null);
        Logger log = LoggerFactory.getLogger("SubData");

        if (config.get().getMap("Settings").getMap("SubData").getRawString("Password", "").length() > 0) {
            subprotocol.registerCipher("AES", new AES(128, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));
            subprotocol.registerCipher("AES-128", new AES(128, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));
            subprotocol.registerCipher("AES-192", new AES(192, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));
            subprotocol.registerCipher("AES-256", new AES(256, config.get().getMap("Settings").getMap("SubData").getRawString("Password")));

            log.info("AES Encryption Available");
        }
        if (new UniversalFile(dir, "subdata.rsa.key").exists()) {
            try {
                subprotocol.registerCipher("RSA", new RSA(new UniversalFile(dir, "subdata.rsa.key")));
                log.info("RSA Encryption Available");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        reconnect = true;
        log.info(" ");
        log.info("Connecting to /" + config.get().getMap("Settings").getMap("SubData").getRawString("Address", "127.0.0.1:4391"));
        connect(null);

        if (notifyPlugins) {
            List<Runnable> listeners = api.reloadListeners;
            if (listeners.size() > 0) {
                for (Object obj : listeners) {
                    try {
                        ((Runnable) obj).run();
                    } catch (Throwable e) {
                        new InvocationTargetException(e, "Problem reloading plugin").printStackTrace();
                    }
                }
            }
        }
    }

    private void connect(NamedContainer<DisconnectReason, DataClient> disconnect) throws IOException {
        int reconnect = config.get().getMap("Settings").getMap("SubData").getInt("Reconnect", 30);
        if (disconnect == null || (this.reconnect && reconnect > 0 && disconnect.name() != DisconnectReason.PROTOCOL_MISMATCH && disconnect.name() != DisconnectReason.ENCRYPTION_MISMATCH)) {
            long reset = resetDate;
            Logger log = LoggerFactory.getLogger("SubData");
            Sponge.getScheduler().createTaskBuilder().async().execute(new Runnable() {
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
                    } catch (IOException e) {
                        log.info("Connection was unsuccessful, retrying in " + reconnect + " seconds");

                        Sponge.getScheduler().createTaskBuilder().async().execute(this).delay(reconnect, TimeUnit.SECONDS).submit(plugin);
                    }
                }
            }).delay((disconnect == null)?0:reconnect, TimeUnit.SECONDS).submit(plugin);
        }
    }

    /**
     * Disable Plugin
     */
    @Listener
    public void disable(GameStoppingEvent event) {
        running = false;
        if (subdata != null) try {
            reconnect = false;

            ArrayList<SubDataClient> temp = new ArrayList<SubDataClient>();
            temp.addAll(subdata.values());
            for (SubDataClient client : temp) if (client != null)  {
                client.close();
                client.waitFor();
            }
            subdata.clear();
            subdata.put(0, null);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
