package kvj.taskw.data;

import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.kvj.bravo7.log.Logger;
import org.kvj.bravo7.util.Listeners;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

import kvj.taskw.App;
import kvj.taskw.sync.SSLHelper;
import kvj.taskw.ui.MainListAdapter;

/**
 * Created by vorobyev on 11/17/15.
 */
public class AccountController {

    public interface TaskListener {
        public void onStart();
        public void onFinish();
    }

    private Listeners<TaskListener> taskListeners = new Listeners<TaskListener>() {
        @Override
        protected void onAdd(TaskListener listener) {
            super.onAdd(listener);
            if (active) { // Run onStart
                listener.onStart();
            }
        }
    };

    private static final String SYNC_SOCKET = "taskwarrior.sync.";
    public static final String TASKRC = ".taskrc.android";
    public static final String DATA_FOLDER = "data";
    private final Controller controller;
    private final String name;
    private boolean active = false;

    Logger logger = Logger.forInstance(this);

    private final LocalServerSocket syncSocket;
    private final File tasksFolder;

    public interface StreamConsumer {
        public void eat(String line);
    }

    private class ToLogConsumer implements StreamConsumer {

        private final Logger.LoggerLevel level;
        private final String prefix;

        private ToLogConsumer(Logger.LoggerLevel level, String prefix) {
            this.level = level;
            this.prefix = prefix;
        }

        @Override
        public void eat(String line) {
            logger.log(level, prefix, line);
        }
    }

    private StreamConsumer errConsumer = new ToLogConsumer(Logger.LoggerLevel.Warning, "ERR:");
    private StreamConsumer outConsumer = new ToLogConsumer(Logger.LoggerLevel.Info, "STD:");

    public AccountController(Controller controller, String name) {
        this.controller = controller;
        this.name = name.toLowerCase();
        tasksFolder = initTasksFolder();
        syncSocket = openLocalSocket(SYNC_SOCKET+this.name);
    }

    private class StringAggregator implements StreamConsumer {

        StringBuilder builder = new StringBuilder();

        @Override
        public void eat(String line) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }

        private String text() {
            return builder.toString();
        }
    }

    public String callSync() {
        if (null == syncSocket) {
            return "Sync not configured";
        }
        StringAggregator err = new StringAggregator();
        StringAggregator out = new StringAggregator();
        boolean result = callTask(out, err, "rc.taskd.socket=" + SYNC_SOCKET + name, "sync");
        logger.d("Sync result:", result, "ERR:", err.text(), "OUT:", out.text());
        return result? null: err.text();
    }

    Pattern linePatthern = Pattern.compile("^([A-Za-z0-9\\._]+)\\s+(\\S.*)$");

    private Map<String, String> taskSettings(final String... names) {
        final Map<String, String> result = new LinkedHashMap<>();
        callTask(new StreamConsumer() {
            @Override
            public void eat(String line) {
                Matcher m = linePatthern.matcher(line);
                if (m.find()) {
                    String keyName = m.group(1).trim();
                    String keyValue = m.group(2).trim();
                    for (String name : names) {
                        if (name.equalsIgnoreCase(keyName)) {
                            result.put(name, keyValue);
                            break;
                        }
                    }
                }
            }
        }, errConsumer, "show");
        return result;
    }

    abstract private class PatternLineConsumer implements StreamConsumer {

        @Override
        public void eat(String line) {
            Matcher m = linePatthern.matcher(line);
            if (m.find()) {
                String keyName = m.group(1).trim();
                String keyValue = m.group(2).trim();
                eat(keyName, keyValue);
            }
        }

        abstract void eat(String key, String value);
    }

    public Map<String, String> taskReports() {
        final Map<String, String> result = new LinkedHashMap<>();
        callTask(new PatternLineConsumer() {

            @Override
            void eat(String key, String value) {
                result.put(key, value);
            }
        }, errConsumer, "reports");
        return result;
    }

    public ReportInfo taskReportInfo(String name) {
        final ReportInfo info = new ReportInfo();
        callTask(new PatternLineConsumer() {

            @Override
            void eat(String key, String value) {
                if (key.endsWith(".columns")) {
                    String[] parts = value.split(",");
                    for (String p : parts) {
                        String name = p;
                        String type = "";
                        if (p.contains(".")) {
                            name = p.substring(0, p.indexOf("."));
                            type = p.substring(p.indexOf(".")+1);
                        }
                        info.fields.put(name, type);
                    }
                }
                if (key.endsWith(".sort")) {
                    String[] parts = value.split(",");
                    for (String p : parts) {
                        if (p.endsWith("/")) p = p.substring(0, p.length()-1);
                        info.sort.put(p.substring(0, p.length()-1), p.charAt(p.length()-1) == '+');
                    }
                }
                if (key.endsWith(".filter")) {
                    info.query = value;
                }
                if (key.endsWith(".description")) {
                    info.description = value;
                }
            }
        }, errConsumer, "show", String.format("report.%s", name));
        info.priorities = taskPriority();
        return info;
    }

    public List<String> taskPriority() {
        // Get all priorities
        final List<String> result = new ArrayList<>();
        callTask(new PatternLineConsumer() {

            @Override
            void eat(String key, String value) {
                for (String p : value.split(",")) { // Split by ,
                    result.add(p);
                }
                logger.d("Parsed priority:", value, result);
            }
        }, errConsumer, "show", "uda.priority.values");
        return result;
    }

    private Thread readStream(InputStream stream, final StreamConsumer consumer) {
        final BufferedReader reader;
        try {
            reader = new BufferedReader(new InputStreamReader(stream, "utf-8"));
        } catch (UnsupportedEncodingException e) {
            logger.e("Error opening stream");
            return null;
        }
        Thread thread = new Thread() {
            @Override
            public void run() {
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        if (null != consumer) {
                            consumer.eat(line);
                        }
                    }
                } catch (Exception e) {
                    logger.e(e, "Error reading stream");
                } finally {
                    try {
                        reader.close();
                    } catch (IOException e) {
                    }
                }
            }
        };
        thread.start();
        return thread;
    }

    private File initTasksFolder() {
        File folder = new File(controller.context().getExternalFilesDir(null), name);
        if (!folder.exists() || !folder.isDirectory()) {
            return null;
        }
        return folder;
    }

    private synchronized boolean callTask(StreamConsumer out, StreamConsumer err, String... arguments) {
        active = true;
        taskListeners.emit(new Listeners.ListenerEmitter<TaskListener>() {
            @Override
            public boolean emit(TaskListener listener) {
                listener.onStart();
                return true;
            }
        });
        try {
            if (null == controller.executable) {
                throw new RuntimeException("Invalid executable");
            }
            if (null == tasksFolder) {
                throw new RuntimeException("Invalid folder");
            }
            List<String> args = new ArrayList<>();
            args.add(controller.executable);
            args.add("rc.color=off");
            args.add("rc.verbose=nothing");
            Collections.addAll(args, arguments);
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(controller.context().getFilesDir());
            pb.environment().put("TASKRC", new File(tasksFolder, TASKRC).getAbsolutePath());
            pb.environment().put("TASKDATA", new File(tasksFolder, DATA_FOLDER).getAbsolutePath());
            Process p = pb.start();
            logger.d("Calling now:", tasksFolder, args);
            Thread outThread = readStream(p.getInputStream(), out);
            Thread errThread = readStream(p.getErrorStream(), err);
            int exitCode = p.waitFor();
            logger.d("Exit code:", exitCode, arguments.length);
            if (null != outThread) outThread.join();
            if (null != errThread) errThread.join();
            return 0 == exitCode;
        } catch (Exception e) {
            logger.e(e, "Failed to execute task");
            return false;
        } finally {
            taskListeners.emit(new Listeners.ListenerEmitter<TaskListener>() {
                @Override
                public boolean emit(TaskListener listener) {
                    listener.onFinish();
                    return true;
                }
            });
            active = false;
        }
    }

    private class LocalSocketThread extends Thread {

        private final Map<String, String> config;
        private final LocalSocket socket;

        private LocalSocketThread(Map<String, String> config, LocalSocket socket) {
            this.config = config;
            this.socket = socket;
        }

        private void recvSend(InputStream from, OutputStream to) throws IOException {
            byte[] head = new byte[4]; // Read it first
            from.read(head);
            to.write(head);
            to.flush();
            long size = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            long bytes = 4;
            byte[] buffer = new byte[1024];
            logger.d("Will transfer:", size);
            while (bytes < size) {
                int recv = from.read(buffer);
//                logger.d("Actually get:", recv);
                if (recv == -1) {
                    return;
                }
                to.write(buffer, 0, recv);
                to.flush();
                bytes += recv;
            }
            logger.d("Transfer done", bytes, size);
        }

        @Override
        public void run() {
            Socket remoteSocket = null;
            try {
                String host = config.get("taskd.server");
                int lastColon = host.lastIndexOf(":");
                int port = Integer.parseInt(host.substring(lastColon + 1));
                host = host.substring(0, lastColon);
                SSLSocketFactory factory = SSLHelper.tlsSocket(
                    new FileInputStream(config.get("taskd.ca")),
                    new FileInputStream(config.get("taskd.certificate")),
                    new FileInputStream(config.get("taskd.key")));
                logger.d("Connecting to:", host, port);
                remoteSocket = factory.createSocket(host, port);
                InputStream localInput = socket.getInputStream();
                OutputStream localOutput = socket.getOutputStream();
                InputStream remoteInput = remoteSocket.getInputStream();
                OutputStream remoteOutput = remoteSocket.getOutputStream();
                logger.d("Connected, will read first piece");
                recvSend(localInput, remoteOutput);
                recvSend(remoteInput, localOutput);
                logger.d("Sync success");
            } catch (Exception e) {
                logger.e(e, "Failed to transfer data");
            } finally {
                if (null != remoteSocket) {
                    try {
                        remoteSocket.close();
                    } catch (IOException e) {
                    }
                }
                try {
                    socket.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private LocalServerSocket openLocalSocket(String name) {
        try {
            final Map<String, String> config = taskSettings("taskd.ca", "taskd.certificate", "taskd.key", "taskd.server");
            logger.d("Will run with config:", config);
            if (!config.containsKey("taskd.server")) {
                // Not configured
                logger.d("Sync not configured - give up");
                return null;
            }
            final LocalServerSocket socket = new LocalServerSocket(name);
            Thread acceptThread = new Thread() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            LocalSocket conn = socket.accept();
                            logger.d("New incoming connection");
                            new LocalSocketThread(config, conn).start();
                        } catch (IOException e) {
                            logger.w(e, "Accept failed");
                        }
                    }
                }
            };
            acceptThread.start();
            return socket; // Close me later on stop
        } catch (IOException e) {
            logger.e(e, "Failed to open local socket");
        }
        return null;
    }

    public List<JSONObject> taskList(String query) {
        final List<JSONObject> result = new ArrayList<>();
        List<String> params = new ArrayList<>();
        params.add("rc.json.array=off");
        params.add("export");
//        params.add(escape(query));
        for (String part : query.split(" ")) { // Split by space and add to list
            params.add(escape(part));
        }
        callTask(new StreamConsumer() {
            @Override
            public void eat(String line) {
                if (!TextUtils.isEmpty(line)) {
                    try {
                        result.add(new JSONObject(line));
                    } catch (Exception e) {
                        logger.e(e, "Not JSON object:", line);
                    }
                }
            }
        }, errConsumer, params.toArray(new String[0]));
        logger.d("List for:", query, result.size());
        return result;
    }

    private String escape(String query) {
        return query.replace(" ", "\\ ").replace("(", "\\(").replace(")", "\\)");
    }

    public boolean intentForEditor(Intent intent, String uuid) {
        intent.putExtra(App.KEY_ACCOUNT, name);
        if (TextUtils.isEmpty(uuid)) { // Done - new item
            return true;
        }
        List<JSONObject> jsons = taskList(uuid);
        if (jsons.isEmpty()) { // Failed
            return false;
        }
        JSONObject json = jsons.get(0);
        intent.putExtra(App.KEY_EDIT_UUID, json.optString("uuid"));
        intent.putExtra(App.KEY_EDIT_DESCRIPTION, json.optString("description"));
        intent.putExtra(App.KEY_EDIT_PROJECT, json.optString("project"));
        JSONArray tags = json.optJSONArray("tags");
        if (null != tags) {
            intent.putExtra(App.KEY_EDIT_TAGS, MainListAdapter.join(" ", MainListAdapter.array2List(tags)));
        }
        intent.putExtra(App.KEY_EDIT_DUE, MainListAdapter.asDate(json.optString("due"), ""));
        intent.putExtra(App.KEY_EDIT_WAIT, MainListAdapter.asDate(json.optString("wait"), ""));
        intent.putExtra(App.KEY_EDIT_SCHEDULED, MainListAdapter.asDate(json.optString("scheduled"), ""));
        intent.putExtra(App.KEY_EDIT_UNTIL, MainListAdapter.asDate(json.optString("until"), ""));
        intent.putExtra(App.KEY_EDIT_RECUR, json.optString("recur"));
        return true;
    }

    public String taskAdd(List<String> changes) {
        List<String> params = new ArrayList<>();
        params.add("add");
        for (String change : changes) { // Copy non-empty
            if (!TextUtils.isEmpty(change)) {
                params.add(change);
            }
        }
        StringAggregator err = new StringAggregator();
        if (!callTask(outConsumer, err, params.toArray(new String[0]))) { // Failure
            return err.text();
        }
        return null; // Success
    }

    public String taskModify(String uuid, List<String> changes) {
        List<String> params = new ArrayList<>();
        params.add(uuid);
        params.add("modify");
        for (String change : changes) { // Copy non-empty
            if (!TextUtils.isEmpty(change)) {
                params.add(change);
            }
        }
        StringAggregator err = new StringAggregator();
        if (!callTask(outConsumer, err, params.toArray(new String[0]))) { // Failure
            return err.text();
        }
        return null; // Success
    }

    public Listeners<TaskListener> listeners() {
        return taskListeners;
    }
}