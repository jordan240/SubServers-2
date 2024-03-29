package net.ME1312.SubServers.Bungee.Host.Internal;

import net.ME1312.SubServers.Bungee.Host.SubLogFilter;
import net.ME1312.SubServers.Bungee.Host.SubLogger;
import net.ME1312.Galaxi.Library.Container;
import net.ME1312.Galaxi.Library.Util;
import net.ME1312.SubServers.Bungee.Library.Compatibility.Logger;
import net.ME1312.SubServers.Bungee.SubAPI;
import net.md_5.bungee.api.ProxyServer;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal Process Logger Class
 */
public class InternalSubLogger extends SubLogger {
    protected Process process;
    private Object handle;
    protected String name;
    protected Container<Boolean> log;
    private List<SubLogFilter> filters = new CopyOnWriteArrayList<>();
    protected File file;
    private PrintWriter writer = null;
    private boolean started = false;
    private Thread out = null;
    private Thread err = null;

    /**
     * Creates a new Internal Process Logger
     *
     * @param process Process
     * @param user Object using this logger (or null)
     * @param name Prefix
     * @param log Console Logging Status
     * @param file File to log to (or null for disabled)
     */
    protected InternalSubLogger(Process process, Object user, String name, Container<Boolean> log, File file) {
        this.process = process;
        this.handle = user;
        this.name = name;
        this.log = log;
        this.file = file;
    }

    @Override
    public void start() {
        started = true;
        if (file != null && writer == null) {
            try {
                this.writer = new PrintWriter(file, "UTF-8");
                this.writer.println("---------- LOG START \u2014 " + name + " ----------");
                this.writer.flush();
            } catch (UnsupportedEncodingException | FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        if (out == null) (out = new Thread(() -> start(process.getInputStream(), false), "SubServers.Bungee::Internal_Log_Spooler(" + name + ')')).start();
        if (err == null) (err = new Thread(() -> start(process.getErrorStream(), true), "SubServers.Bungee::Internal_Error_Spooler(" + name + ')')).start();
        List<SubLogFilter> filters = new ArrayList<SubLogFilter>();
        filters.addAll(this.filters);
        for (SubLogFilter filter : filters) try {
            filter.start();
        } catch (Throwable e) {
            new InvocationTargetException(e, "Exception while running SubLogger Event").printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
    private void start(InputStream in, boolean isErr) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = br.readLine()) != null) {
                log(line);
            }
        } catch (IOException e) {} finally {
            if (isErr) {
                err = null;
            } else {
                out = null;
            }

            stop();
        }
    }

    private void log(String line) {
        if (!line.startsWith(">")) {
            String msg = line;
            Level level;

            // REGEX Formatting
            String type = "";
            Matcher matcher = Pattern.compile("^((?:\\s*\\[?([0-9]{2}:[0-9]{2}:[0-9]{2})]?)?[\\s\\/\\\\\\|]*(?:\\[|\\[.*\\/)?(MESSAGE|INFO|WARNING|WARN|ERROR|ERR|SEVERE)\\]?:?(?:\\s*>)?\\s*)").matcher(msg.replaceAll("\u001B\\[[;\\d]*m", ""));
            while (matcher.find()) {
                type = matcher.group(3).toUpperCase();
            }

            msg = msg.replaceAll("^((?:\\s*\\[?([0-9]{2}:[0-9]{2}:[0-9]{2})]?)?[\\s\\/\\\\\\|]*(?:\\[|\\[.*\\/)?(MESSAGE|INFO|WARNING|WARN|ERROR|ERR|SEVERE)\\]?:?(?:\\s*>)?\\s*)", "");

            // Determine LOG LEVEL
            switch (type) {
                case "WARNING":
                case "WARN":
                    level = Level.WARNING;
                    break;
                case "SEVERE":
                case "ERROR":
                case "ERR":
                    level = Level.SEVERE;
                    break;
                default:
                    level = Level.INFO;
            }

            // Filter Message
            boolean allow = (SubAPI.getInstance().getInternals().sudo == getHandler() && SubAPI.getInstance().getInternals().canSudo) || (log.get() && (SubAPI.getInstance().getInternals().sudo == null || !SubAPI.getInstance().getInternals().canSudo));
            List<SubLogFilter> filters = new ArrayList<SubLogFilter>();
            filters.addAll(this.filters);
            for (SubLogFilter filter : filters)
                try {
                    allow = (filter.log(level, msg) && allow);
                } catch (Throwable e) {
                    new InvocationTargetException(e, "Exception while running SubLogger Event").printStackTrace();
                }

            // Log to CONSOLE
            if (allow) Logger.get(name).log(level, msg);

            // Log to FILE
            if (writer != null) {
                writer.println(line);
                writer.flush();
            }
        }
    }

    @Override
    public void stop() {
        if (out != null) out.interrupt();
        if (err != null) err.interrupt();
        destroy();
    }

    @Override
    public void registerFilter(SubLogFilter filter) {
        if (Util.isNull(filter)) throw new NullPointerException();
        filters.add(filter);
    }

    @Override
    public void unregisterFilter(SubLogFilter filter) {
        if (Util.isNull(filter)) throw new NullPointerException();
        Util.isException(() -> filters.remove(filter));
    }

    private void destroy() {
        if (started) {
            started = false;
            List<SubLogFilter> filters = new ArrayList<SubLogFilter>();
            filters.addAll(this.filters);
            for (SubLogFilter filter : filters) try {
                filter.stop();
            } catch (Throwable e) {
                new InvocationTargetException(e, "Exception while running SubLogger Event").printStackTrace();
            }
            if (writer != null) {
                PrintWriter writer = this.writer;
                this.writer = null;
                int l = (int) Math.floor((("---------- LOG START \u2014 " + name + " ----------").length() - 9) / 2);
                String s = "";
                while (s.length() < l) s += '-';
                if (writer != null) {
                    writer.println(s + " LOG END " + s);
                    writer.close();
                }
            }
        }
    }

    @Override
    public Object getHandler() {
        return handle;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isLogging() {
        return log.get();
    }
}
