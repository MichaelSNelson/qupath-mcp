package io.earthmover.qupath.driver;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.stage.Window;
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
 */
class ActionLog {

    private static final Logger logger = LoggerFactory.getLogger(ActionLog.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** The returned text kept per entry; a long qupath_describe result is cut to this. */
    static final int RESULT_CHARS = 4000;
    /** The returned text shown per entry by {@link #tail}; the file keeps the whole of it. */
    static final int TAIL_CHARS = 400;

    static final BooleanProperty enabled = PathPrefs.createPersistentPreference("mcpActionLog", true);
    static final StringProperty folder = PathPrefs.createPersistentPreference("mcpActionLogDir", "");

    private static final Gson GSON = GsonTools.getInstance().newBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /**
     * The entry of the call running on this thread, so the driver can add the window and control it resolved. Inheritable
     * because a Groovy evaluation runs on a thread started by the request's, and its helpers act through the same driver.
     */
    private static final InheritableThreadLocal<Entry> CURRENT = new InheritableThreadLocal<>();

    private final QuPathGUI qupath;
    private final Object lock = new Object();
    private File file;
    private Writer writer;
    private int entries;

    ActionLog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** The folder the log goes in: the preference if set, else {@code mcp-logs} in QuPath's user directory. */
    static File directory() {
        var pref = folder.get();
        if (pref != null && !pref.isBlank())
            return new File(pref);
        var user = PathPrefs.userPathProperty().get();
        var base = user != null && !user.isBlank() ? new File(user) : new File(System.getProperty("user.home"), "QuPath");
        return new File(base, "mcp-logs");
    }

    /** Times one action; call {@link Entry#done} or {@link Entry#failed} when it finishes. */
    Entry begin(String source, String client, String tool, Map<String, Object> args) {
        var entry = new Entry(source, client, tool, args);
        CURRENT.set(entry);
        return entry;
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
                    e.put("target", targets.size() == 1 ? targets.get(0) : String.join("; ", targets));
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

    /** The last {@code limit} entries of this session, pretty-printed, after a line naming the file. */
    String tail(int limit) {
        File f;
        int n;
        synchronized (lock) {
            f = file;
            n = entries;
        }
        if (f == null)
            return "No actions logged yet this session. Log folder: " + directory();
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath());
        } catch (IOException e) {
            return "Could not read " + f + ": " + e.getMessage();
        }
        var from = Math.max(0, lines.size() - Math.max(1, limit));
        var out = new ArrayList<String>();
        out.add("%s — %d entries, showing %d–%d".formatted(f, n, from + 1, lines.size()));
        for (var l : lines.subList(from, lines.size()))
            try {
                var e = JsonParser.parseString(l).getAsJsonObject();
                // The file keeps the whole result; the view only needs enough to recognise it.
                for (var key : List.of("result", "error"))
                    if (e.has(key) && e.get(key).isJsonPrimitive() && e.get(key).getAsString().length() > TAIL_CHARS)
                        e.addProperty(key, e.get(key).getAsString().substring(0, TAIL_CHARS) + "… [full text in the file]");
                out.add(PRETTY.toJson(e));
            } catch (RuntimeException e) {
                out.add(l);
            }
        return String.join("\n", out);
    }

    private void append(String line) {
        synchronized (lock) {
            try {
                if (writer == null) {
                    var dir = directory();
                    Files.createDirectories(dir.toPath());
                    file = new File(dir, "mcp-" + FILE_STAMP.format(OffsetDateTime.now()) + ".jsonl");
                    writer = new FileWriter(file, true);
                    logger.info("Agent actions logged to {}", file);
                }
                writer.write(line);
                writer.write('\n');
                writer.flush();
                entries++;
            } catch (IOException e) {
                logger.error("Could not write the action log", e);
            }
        }
    }
}
