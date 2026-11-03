package dev.mateuszsury.androidagent;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * Captures the README screenshots (home, chat, background services, terminal) of the English
 * build on an emulator. The runtime, services and terminal are real; the conversation is a
 * seeded example, because a model reply needs an account sign-in.
 */
public final class EnglishScreenshots extends Instrumentation {
    private static final String OUT = "Download/androidagent-screenshots/";
    private Context app;
    private UiAutomation ui;
    private File dir;
    private final StringBuilder log = new StringBuilder();
    private int failures;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        app = getTargetContext();
        ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        dir = new File(app.getExternalFilesDir(null), "screenshots");
        dir.mkdirs();
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
        Prefs.setOnboarded(app, true);
        Prefs.setDevMode(app, true);

        step("runtime", () -> {
            EmbeddedRuntime runtime = EmbeddedRuntime.get(app);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try { worker.submit(() -> { runtime.initializeBlocking(); return null; }).get(900, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); }
            check(runtime.ready(), "Runtime not ready: " + runtime.error());
        });
        step("home", () -> {
            Activity home = open(new Intent(app, MainActivity.class));
            shot("redesign-home.png");
            close(home);
        });
        step("chat", () -> {
            String[] id = new String[1];
            runOnMainSync(() -> id[0] = seedChat());
            Activity chat = open(new Intent(app, ChatActivity.class).putExtra("id", id[0]));
            shot("redesign-chat.png");
            close(chat);
        });
        step("services", () -> {
            ServiceManager services = ServiceManager.get(app);
            service(services, "web", 8080, "mkdir -p site && cd site && echo \"<h1>androidagent webserver OK</h1>\" > index.html && exec python3 -m http.server 8080 --bind 0.0.0.0");
            service(services, "probe", 0, "python3 --version; node --version; git --version; uname -m; id; sleep 600");
            SystemClock.sleep(8000);
            Activity screen = open(new Intent(app, ServicesActivity.class));
            SystemClock.sleep(4000);
            shot("redesign-services.png");
            close(screen);
        });
        step("terminal", () -> {
            Activity terminal = open(new Intent(app, TerminalActivity.class).putExtra("mode", "shell"));
            awaitEnabled(terminal, "Send", 120000);
            SystemClock.sleep(2000);
            runOnMainSync(() -> {
                EditText input = findInput(terminal.getWindow().getDecorView());
                check(input != null, "Terminal input missing");
                input.setText("python3 -c 'import platform; print(\"Python\", platform.python_version(), \"6*7 =\", 6*7)'; node -e 'console.log(\"Node.js\", process.version)'; ls --color=always -d $PREFIX/bin $PREFIX/lib");
                findLabel(terminal.getWindow().getDecorView(), "Send").performClick();
            });
            awaitText(terminal, "Node.js v", 60000);
            SystemClock.sleep(3000);
            runOnMainSync(() -> {
                View focus = terminal.getCurrentFocus();
                if (focus != null) focus.clearFocus();
                ((android.view.inputmethod.InputMethodManager) terminal.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(terminal.getWindow().getDecorView().getWindowToken(), 0);
            });
            SystemClock.sleep(2000);
            shot("redesign-terminal.png");
            close(terminal);
        });
        try { ServiceManager.get(app).stopAll(); } catch (Throwable ignored) { }
        try { write(new File(dir, "log.txt"), log.toString()); export(); } catch (Throwable e) { log.append("export: ").append(e).append('\n'); failures++; }
        Bundle result = new Bundle();
        result.putString("stream", log + "Screenshots: " + failures + " failures\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /** An example conversation in the shape ChatHub records from a real Claude Code run. */
    private String seedChat() {
        Sessions sessions = ChatHub.get(app).sessions;
        Sessions.Session s = sessions.create("claude");
        s.model = "sonnet";
        sessions.add(s, Bubble.Kind.USER, "Set up a simple web server on port 8080 and check that it works.");
        sessions.add(s, Bubble.Kind.BUDDY, "Sure — I'll create a folder and a server file, then run it as a background service.");
        tool(sessions, s, "Bash", "mkdir -p site && cd site", "ok", null, 0);
        tool(sessions, s, "Write", "site/server.py", "ok", "Wrote 12 lines to site/server.py", 1);
        tool(sessions, s, "mcp__phone__service_start", "name: web, port: 8080", "ok",
            "Started service web (pid 4312)\nListening on 0.0.0.0:8080\nhttp://192.168.1.23:8080", 3);
        tool(sessions, s, "Bash", "curl -s localhost:8080 | head -3", "ok", "<!DOCTYPE HTML>\n<html lang=\"en\">\n<head>", 3);
        tool(sessions, s, "mcp__phone__device_status", "", "error", "Error: connection refused", 1);
        sessions.add(s, Bubble.Kind.BUDDY, "## Server is running ✓\n\nI started a simple HTTP server in the background and checked that it responds. Summary:\n\n"
            + "- **Service:** `web` on port **8080**\n- **On this phone:** http://127.0.0.1:8080\n- **On your Wi-Fi:** http://192.168.1.23:8080\n\n"
            + "You can stop it any time under *Background services*.");
        sessions.select(s);
        return s.id;
    }

    private static void tool(Sessions sessions, Sessions.Session s, String name, String args, String state, String result, int lines) {
        try {
            JSONObject meta = new JSONObject().put("tool", name).put("args", args).put("id", "toolu_" + name.hashCode() + args.length()).put("state", state);
            if (result != null) meta.put("result", result).put("lines", lines);
            String shortName = name.startsWith("mcp__") ? name.substring(name.lastIndexOf("__") + 2) : name;
            sessions.add(s, Bubble.Kind.STEP, args.isEmpty() ? shortName : shortName + "(" + args + ")", meta);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void service(ServiceManager services, String name, int port, String command) {
        ServiceManager.Definition d = new ServiceManager.Definition();
        d.name = name;
        d.port = port;
        d.command = command;
        d.restart = ServiceManager.RESTART_NEVER;
        services.define(d, "owner");
        services.start(name);
        ServiceManager.Status st = services.awaitSettled(name, 60000);
        check(st != null && st.active(), "Service " + name + " did not start: " + (st == null ? "missing" : st.state + " " + st.error));
    }

    // ------------------------------------------------------------------ helpers

    interface Step { void run() throws Exception; }

    private void step(String name, Step step) {
        try { step.run(); log.append("OK ").append(name).append('\n'); }
        catch (Throwable e) {
            failures++;
            log.append("FAIL ").append(name).append(": ").append(e).append('\n');
            try { shot("failed-" + name + ".png"); } catch (Throwable ignored) { }
        }
        Bundle status = new Bundle();
        status.putString("stream", log.substring(log.lastIndexOf("\n", log.length() - 2) + 1));
        sendStatus(0, status);
    }

    private Activity open(Intent intent) {
        Activity a = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        SystemClock.sleep(5000);
        return a;
    }

    private void close(Activity a) {
        runOnMainSync(a::finish);
        waitForIdleSync();
        SystemClock.sleep(1000);
    }

    private void shot(String name) throws IOException {
        // Apps may no longer send this broadcast themselves; the shell still can.
        try { ui.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close(); } catch (IOException ignored) { }
        waitForIdleSync();
        SystemClock.sleep(1500);
        Bitmap frame = ui.takeScreenshot();
        check(frame != null, "Screenshot is unavailable");
        try (OutputStream out = new FileOutputStream(new File(dir, name))) { frame.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { frame.recycle(); }
    }

    private void awaitEnabled(Activity a, String label, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            boolean[] ok = {false};
            runOnMainSync(() -> { View v = findLabel(a.getWindow().getDecorView(), label); ok[0] = v != null && v.isEnabled(); });
            if (ok[0]) return;
            SystemClock.sleep(250);
        }
        throw new AssertionError("Control never became ready: " + label);
    }

    private void awaitText(Activity a, String text, long timeout) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) {
            boolean[] ok = {false};
            runOnMainSync(() -> ok[0] = contains(a.getWindow().getDecorView(), text));
            if (ok[0]) return;
            SystemClock.sleep(250);
        }
        throw new AssertionError("Text never appeared: " + text);
    }

    private static View findLabel(View v, String label) {
        if (v instanceof TextView && !(v instanceof EditText) && label.contentEquals(((TextView) v).getText())) return v;
        if (label.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View found = findLabel(((ViewGroup) v).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static EditText findInput(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            EditText found = findInput(((ViewGroup) v).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean contains(View v, String text) {
        if (v instanceof TextView && !(v instanceof EditText) && ((TextView) v).getText().toString().contains(text)) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
            if (contains(((ViewGroup) v).getChildAt(i), text)) return true;
        return false;
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static void write(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) { out.write(s.getBytes("UTF-8")); }
    }

    /** Publishes the files to Downloads, where adb can pull them from a non-debuggable app. */
    private void export() throws Exception {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            app.getContentResolver().delete(collection, "_display_name = ? AND relative_path = ?", new String[]{name, OUT});
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith(".png") ? "image/png" : "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, OUT);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri dest = app.getContentResolver().insert(collection, values);
            if (dest == null) throw new IOException("Cannot publish " + name);
            try (InputStream in = new FileInputStream(f); OutputStream out = app.getContentResolver().openOutputStream(dest)) {
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            app.getContentResolver().update(dest, values, null, null);
        }
    }
}
