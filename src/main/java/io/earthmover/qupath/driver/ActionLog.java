package io.earthmover.qupath.driver;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.stage.Window;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.io.GsonTools;

/**
 * Records every action the agent takes, one JSON object per line, so a session can be checked afterwards or redone.
 * QuPath keeps no history of its own, so without this a run driven by an agent leaves no record of what was done.
 *
 * <p>An entry is written for each MCP tool call, each {@code /groovy} request and each start-up script, whatever the
 * on-screen indicator is set to: the indicator is for the person watching, the log is for the record. The unit is the
 * tool call, because that is the unit the agent acts in: it asks for a click on a named control rather than moving a
 * pointer, so nothing finer exists to record. Each entry carries the tool, its arguments verbatim (the Groovy source,
 * for {@code qupath_run_groovy}), the window and control the driver resolved them to, whether it succeeded, how long it
 * took, the text it returned, and the project and image that were open.
 *
 * <p>One file per QuPath session, {@code mcp-<date>-<time>.jsonl} in the log folder, created on the first action so a
 * session with no agent activity leaves nothing behind. Lines are appended and flushed as they happen, so the log is
 * intact up to the action that was running if QuPath goes down. The folder defaults to {@code mcp-logs} in QuPath's
 * user directory (preference "Action log folder").
 *
 * <p>Logs are kept until the user removes them unless "Delete action logs after (days)" is set, because the log is the
 * record a session is checked or reproduced from. When a session opens its file, logs older than that are deleted, and
 * if the folder is still over "Warn when action logs exceed (MB)" the user gets a notification and the agent a note in
 * its next tool result, so whoever is driving finds out.
 */
class ActionLog {

    private static final Logger logger = LoggerFactory.getLogger(ActionLog.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** The returned text kept per entry; a long qupath_describe result is cut to this. */
    static final int RESULT_CHARS = 4000;

    static final BooleanProperty enabled = PathPrefs.createPersistentPreference("mcpActionLog", true);
    static final StringProperty folder = PathPrefs.createPersistentPreference("mcpActionLogDir", "");
    static final IntegerProperty keepDays = PathPrefs.createPersistentPreference("mcpActionLogKeepDays", 0);
    static final IntegerProperty warnMB = PathPrefs.createPersistentPreference("mcpActionLogWarnMB", 1024);

    private static final Gson GSON = GsonTools.getInstance().newBuilder().disableHtmlEscaping().create();

    /**
     * The entry of the call running on this thread, so the driver can add the window and control it resolved. Not
     * inheritable: thread pools started during a call would keep a finished entry alive. A Groovy evaluation, which runs
     * on a thread of its own, is handed its caller's entry with {@link #attach}.
     */
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

    private final QuPathGUI qupath;
    private File file;
    private Writer writer;
    private volatile String warning;

    ActionLog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** The folder the log goes in: the preference if set, else {@code mcp-logs} in QuPath's user directory. */
    static File directory() {
        var pref = folder.get();
        if (pref != null && !pref.isBlank())
            return new File(pref);
        var user = PathPrefs.userPathProperty().get();
        var base = user != null && !user.isBlank() ? new File(user) : PathPrefs.getDefaultQuPathUserDirectory().toFile();
        return new File(base, "mcp-logs");
    }

    /** Times one action; call {@link Entry#done} or {@link Entry#failed} when it finishes. */
    Entry begin(String source, String client, String tool, Map<String, Object> args) {
        var entry = new Entry(source, client, tool, args);
        CURRENT.set(entry);
        return entry;
    }

    /** The entry of the call running on this thread, or null; pass it to {@link #attach} on a thread the call starts. */
    static Entry current() {
        return CURRENT.get();
    }

    /** A note for the agent about the size of the log folder, at most once per check; null when there is nothing to say. */
    String takeWarning() {
        var w = warning;
        warning = null;
        return w;
    }

    static void attach(Entry entry) {
        CURRENT.set(entry);
    }

    static void target(Node node, String action) {
        target(node == null || node.getScene() == null ? null : node.getScene().getWindow(), action);
    }

    /**
     * Notes what the running call acted on: the window's title and the driver's description of the action on the control,
     * such as {@code click “Apply”} or {@code set “Size:” to 7}. A call that acts more than once (a Groovy script) lists each.
     */
    static void target(Window window, String action) {
        var entry = CURRENT.get();
        if (entry == null)
            return;
        synchronized (entry) {
            if (entry.window == null && window != null)
                entry.window = GuiDriver.titleOf(window);
            entry.targets.add(action);
        }
    }

    class Entry {
        private final OffsetDateTime time = OffsetDateTime.now();
        private final long start = System.nanoTime();
        private final String source, client, tool;
        private final Map<String, Object> args;
        private String window;
        private final List<String> targets = new ArrayList<>();

        private Entry(String source, String client, String tool, Map<String, Object> args) {
            this.source = source;
            this.client = client;
            this.tool = tool;
            this.args = args;
        }

        void done(String result) {
            write(true, result, null);
        }

        void failed(String error) {
            write(false, null, error);
        }

        private void write(boolean ok, String result, String error) {
            if (CURRENT.get() == this)
                CURRENT.remove();
            if (!enabled.get())
                return;
            var e = new LinkedHashMap<String, Object>();
            e.put("time", time.toString());
            e.put("source", source);
            if (client != null)
                e.put("client", client);
            e.put("tool", tool);
            e.put("args", args == null ? Map.of() : args);
            synchronized (this) {
                if (window != null)
                    e.put("window", window);
                if (!targets.isEmpty())
                    e.put("target", String.join("; ", targets));
            }
            e.put("ok", ok);
            e.put("ms", Math.round((System.nanoTime() - start) / 1e6));
            if (result != null)
                e.put("result", result.length() > RESULT_CHARS ? result.substring(0, RESULT_CHARS) + "…" : result);
            if (error != null)
                e.put("error", error);
            // What the action acted on, so the line stands on its own.
            try {
                var project = qupath.getProject();
                if (project != null)
                    e.put("project", project.getPath().toString());
                var image = qupath.getImageData();
                if (image != null)
                    e.put("image", image.getServerPath());
            } catch (RuntimeException ignored) {
                // A project or image mid-change; the entry is still worth keeping.
            }
            append(GSON.toJson(e));
        }
    }

    private synchronized void append(String line) {
        try {
            var dir = directory();
            // The folder preference can change mid-session; the rest of the session goes to the new folder.
            if (writer != null && !dir.getAbsoluteFile().equals(file.getParentFile().getAbsoluteFile())) {
                writer.close();
                writer = null;
            }
            if (writer == null) {
                Files.createDirectories(dir.toPath());
                var name = file != null ? file.getName() : "mcp-" + FILE_STAMP.format(OffsetDateTime.now()) + ".jsonl";
                file = new File(dir, name);
                writer = new FileWriter(file, StandardCharsets.UTF_8, true);
                logger.info("Agent actions logged to {}", file);
                tidy(dir);
            }
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            logger.error("Could not write the action log", e);
        }
    }

    /** Deletes logs past the retention period, then warns if what is left is over the size threshold. */
    private void tidy(File dir) {
        var logs = dir.listFiles((d, n) -> n.startsWith("mcp-") && n.endsWith(".jsonl"));
        if (logs == null)
            return;
        int days = keepDays.get();
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        long bytes = 0;
        for (var log : logs) {
            if (days > 0 && !log.equals(file) && log.lastModified() < cutoff) {
                if (log.delete())
                    logger.info("Deleted action log {}, older than {} days", log, days);
                continue;
            }
            bytes += log.length();
        }
        int limit = warnMB.get();
        if (limit <= 0 || bytes <= limit * 1024L * 1024L)
            return;
        double mb = bytes / (1024.0 * 1024);
        var size = mb < 1024 ? "%.0f MB".formatted(mb) : "%.1f GB".formatted(mb / 1024);
        logger.warn("Action logs in {} total {}, over the {} MB warning threshold", dir, size, limit);
        Platform.runLater(() -> Dialogs.showWarningNotification("MCP action logs",
                "The agent action logs in %s total %s. Delete old ones, or set \"Delete action logs after (days)\" in the MCP server preferences."
                        .formatted(dir, size)));
        warning = ("Note: QuPath's agent action logs in %s total %s, over the %d MB warning threshold. Tell the user; "
                + "don't delete them yourself, they are the record of past sessions.").formatted(dir, size, limit);
    }
}
