package net.ME1312.SubServers.Host.Network.Packet;

import net.ME1312.SubServers.Host.Library.Config.YAMLSection;
import net.ME1312.SubServers.Host.Library.Log.Logger;
import net.ME1312.SubServers.Host.Library.NamedContainer;
import net.ME1312.SubServers.Host.Library.Util;
import net.ME1312.SubServers.Host.Library.Version.Version;
import net.ME1312.SubServers.Host.Network.PacketIn;
import net.ME1312.SubServers.Host.Network.PacketOut;
import net.ME1312.SubServers.Host.Network.SubDataClient;
import net.ME1312.SubServers.Host.ExHost;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Calendar;

public class PacketDownloadLang implements PacketIn, PacketOut {
    private ExHost host;
    private Logger log = null;

    public PacketDownloadLang() {}

    public PacketDownloadLang(ExHost host) {
        if (Util.isNull(host)) throw new NullPointerException();
        this.host = host;
        try {
            Field f = SubDataClient.class.getDeclaredField("log");
            f.setAccessible(true);
            this.log = (Logger) f.get(null);
            f.setAccessible(false);
        } catch (IllegalAccessException | NoSuchFieldException e) {}
    }

    @Override
    public JSONObject generate() {
        return null;
    }

    @Override
    public void execute(JSONObject data) {
        try {
            Field f = ExHost.class.getDeclaredField("lang");
            f.setAccessible(true);
            f.set(host, new NamedContainer<>(Calendar.getInstance().getTime().getTime(), new YAMLSection(data.getJSONObject("Lang")).get()));
            f.setAccessible(false);
            log.info.println("Lang Settings Downloaded");
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Version getVersion() {
        return new Version("2.11.0a");
    }
}
