package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.*;

/** Native dashboard. Works without model sign-in or Termux. */
public class DockerActivity extends Screen {
    private final Handler poll = new Handler(Looper.getMainLooper());
    private TextView status, detail;
    private Dot dot;
    private Button start;
    private Spinner ram, cores;
    private LinearLayout tasks, exports;
    private String lastTasks = null, lastExports = null, lastStart = "";
    private final Set<String> seenTasks = new HashSet<>(), seenExports = new HashSet<>();
    private boolean exportsLoading;
    private DockerVm vm;
    private final Runnable refresh = new Runnable() {
        public void run() {
            render();
            poll.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        vm = DockerVm.get(this);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.addView(topBar(this, "Docker", "~/docker · linux vm · no root"), full());
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = ToolUi.body(this);

        TextView heading = title(this, "A place for your ideas", 21);
        heading.setPadding(dp(this, 20), dp(this, 12), dp(this, 20), 0);
        body.addView(heading, full());
        TextView intro = ToolUi.note(this, "Build and run apps in isolated environments. Keep the results and clean up after the task. No root.", 14);
        intro.setPadding(dp(this, 20), dp(this, 6), dp(this, 20), dp(this, 16));
        body.addView(intro, full());

        // ------------------------------------------------------------ hero: VM state + resources
        LinearLayout hero = terminalBox(this, "environment", ACCENT);
        LinearLayout head = row(this);
        head.setGravity(android.view.Gravity.TOP);
        dot = toolDot(this, FAINT);
        LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16));
        dotP.topMargin = dp(this, 4);
        head.addView(dot, dotP);
        status = title(this, "", 17);
        status.setPadding(dp(this, 8), 0, 0, 0);
        head.addView(status, weight1());
        hero.addView(head, full());
        detail = monoText(this, "", 12, MUTED);
        detail.setLineSpacing(dp(this, 2), 1f);
        detail.setPadding(dp(this, 24), dp(this, 6), 0, dp(this, 4));
        detail.setTextIsSelectable(true);
        hero.addView(detail, full());

        ram = new Spinner(this);
        ram.setContentDescription("Docker environment memory");
        ToolUi.styleSpinner(ram, new String[]{"512 MB RAM", "1 GB RAM", "2 GB RAM"});
        ram.setSelection(state == null ? 1 : state.getInt("ram", 1));
        hero.addView(resource("Memory", ram), resourceParams(14));
        cores = new Spinner(this);
        cores.setContentDescription("Docker environment CPUs");
        ToolUi.styleSpinner(cores, new String[]{"1 CPU · lower load", "2 CPU · faster startup"});
        int defaultCores = !vm.emulatedAndroid && Runtime.getRuntime().availableProcessors() >= 4 ? 1 : 0;
        cores.setSelection(state == null ? defaultCores : state.getInt("cores", defaultCores));
        hero.addView(resource("CPUs", cores), resourceParams(8));

        start = button(this, "Start environment", true);
        start.setOnClickListener(v -> {
            if (vm.alive() || "preparing".equals(vm.state) || "booting".equals(vm.state)) {
                startService(new Intent(this, DockerService.class).setAction("stop"));
            } else {
                int[] sizes = {512, 1024, 2048};
                startForegroundService(new Intent(this, DockerService.class).putExtra("ram", sizes[ram.getSelectedItemPosition()]).putExtra("cores", cores.getSelectedItemPosition() + 1));
                offerNotifications();
            }
        });
        hero.addView(start, ToolUi.buttonParams(this, 16));
        body.addView(hero, cardParams(this));

        // ------------------------------------------------------------ tasks
        body.addView(sectionLabel(this, "Tasks"), full());
        LinearLayout taskCard = ToolUi.group(this);
        tasks = column(this);
        taskCard.addView(tasks, full());
        taskCard.addView(hairline(), dividerParams());
        ToolUi.Row create = new ToolUi.Row(this, R.drawable.ms_add, "New task", "empty project with a Dockerfile", false).accent()
                .onClick(v -> create(false));
        taskCard.addView(create.view, full());
        taskCard.addView(hairline(), dividerParams());
        ToolUi.Row demo = new ToolUi.Row(this, R.drawable.ms_build_circle, "Try the sample app", "web page · container port 8080", true)
                .onClick(v -> create(true));
        taskCard.addView(demo.view, full());
        body.addView(taskCard, cardParams(this));

        // ------------------------------------------------------------ sharing published apps
        LinearLayout sharing = ToolUi.group(this);
        sharing.addView(switchRow(this, "Preview on Wi-Fi network", "Other devices on this network can open the app without signing in",
                DockerVm.lanExposure(this), on -> {
                    DockerVm.setLanExposure(this, on);
                    lastTasks = null; // the next heartbeat re-binds forwards and adds Wi-Fi addresses
                }), full());
        LinearLayout.LayoutParams sp = cardParams(this);
        sp.topMargin = dp(this, 2);
        body.addView(sharing, sp);

        // ------------------------------------------------------------ exports
        body.addView(sectionLabel(this, "Kept files"), full());
        exports = ToolUi.group(this);
        TextView loading = text(this, "Loading…", 14, FAINT);
        loading.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        exports.addView(loading, full());
        body.addView(exports, cardParams(this));

        // ------------------------------------------------------------ maintenance
        body.addView(sectionLabel(this, "Maintenance"), full());
        LinearLayout tools = ToolUi.group(this);
        ToolUi.add(tools, new ToolUi.Row(this, R.drawable.ms_terminal, "Environment log", "Linux console from this boot", true)
                .onClick(v -> showLog()).view);
        ToolUi.add(tools, new ToolUi.Row(this, R.drawable.ms_description, "Save boot diagnostics", "file for a bug report", true)
                .onClick(v -> new Thread(() -> {
                    try {
                        JSONObject saved = vm.exportDiagnostics();
                        runOnUiThread(() -> { if (!isDestroyed()) openExport(saved); });
                    } catch (Exception e) { runOnUiThread(() -> error(e)); }
                }, "docker-diagnostics-export").start()).view);
        ToolUi.add(tools, new ToolUi.Row(this, R.drawable.ms_description, "Licenses and sources", "Linux, QEMU, Docker Engine", true)
                .onClick(v -> showLicenses()).view);
        ToolUi.add(tools, new ToolUi.Row(this, R.drawable.ms_close, "Delete all tasks and cache", "preserved files will remain", false).danger()
                .onClick(v -> confirmSheet(this, "Reset environment?", "This deletes the work disk, tasks and Docker images. Saved files will remain available.", "Delete disk", true, () -> {
                    new Thread(() -> {
                        try {
                            vm.reset();
                            runOnUiThread(() -> { if (!isDestroyed()) { lastTasks = null; render(); } });
                        } catch (Exception e) { runOnUiThread(() -> error(e)); }
                    }, "docker-reset").start();
                })).view);
        body.addView(tools, cardParams(this));
        TextView limits = ToolUi.note(this, "The environment uses the phone's memory and battery. The first start requires unpacking Linux; large projects need extra space.", 12);
        limits.setTextColor(FAINT);
        body.addView(limits, full());

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        render();
        Motion.stagger(body, 30, 35);
    }

    private View hairline() {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        return line;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(this, 1));
        p.leftMargin = dp(this, 16);
        return p;
    }

    private LinearLayout resource(String name, Spinner spinner) {
        LinearLayout r = row(this);
        TextView label = single(monoText(this, name.toLowerCase(Locale.ROOT), 12, FAINT));
        r.addView(label, new LinearLayout.LayoutParams(dp(this, 84), -2));
        r.addView(spinner, new LinearLayout.LayoutParams(0, dp(this, 46), 1));
        return r;
    }

    private LinearLayout.LayoutParams resourceParams(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }

    private void create(boolean demo) {
        new Thread(() -> {
            try {
                JSONObject task = vm.request(new JSONObject().put("op", "create").put("name", demo ? "My web app" : "New task"));
                String id = task.getString("id");
                if (demo) {
                    vm.request(new JSONObject().put("op", "write").put("task", id).put("path", "Dockerfile").put("content",
                            demoAsset("Dockerfile")));
                    vm.request(new JSONObject().put("op", "write").put("task", id).put("path", "serve.sh").put("content", demoAsset("serve.sh")));
                    vm.request(new JSONObject().put("op", "write").put("task", id).put("path", "index.html").put("content",
                            "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>My app</title><style>body{font:20px system-ui;background:#f7f7f5;color:#202020;max-width:600px;margin:15vh auto;padding:24px}button{background:#202020;color:white;border:0;border-radius:24px;padding:14px 24px;font:inherit}</style><h1>Built on the phone.</h1><p>This app runs in Docker in androidagent.</p><button onclick='this.textContent=\"It works!\"'>Try it</button>"));
                }
                runOnUiThread(() -> { if (!isDestroyed()) startActivity(new Intent(this, DockerTaskActivity.class).putExtra("task", id).putExtra("demo", demo)); });
            } catch (Exception e) { runOnUiThread(() -> error(e)); }
        }, "docker-create").start();
    }

    private String demoAsset(String name) throws java.io.IOException {
        try (java.io.InputStream in = getAssets().open("docker/demo/" + name); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void render() {
        String s = vm.state;
        boolean ready = "ready".equals(s), stopped = "stopped".equals(s), failed = "error".equals(s), stopping = "stopping".equals(s);
        boolean running = vm.alive() || "preparing".equals(s) || "booting".equals(s);
        ToolUi.setText(status, ready ? "Environment running" : stopped ? "Environment stopped" : failed ? "Failed to start" : "Preparing environment…");
        dot.setColor(ready ? OK : stopped ? FAINT : failed ? ERR : stopping ? WARN : ACCENT);
        dot.setPulsing(!ready && !stopped && !failed);
        String information = vm.detail;
        if (ready) information = "Docker " + vm.guest.optString("engine_version") + " · " + vm.guest.optString("architecture")
                + "\nFree space: " + String.format(Locale.US, "%.1f GB in the environment · %.1f GB on the phone", vm.guest.optLong("disk_free_bytes") / 1073741824.0, vm.directory.getUsableSpace() / 1073741824.0);
        else if ("booting".equals(s)) {
            JSONObject phase = vm.guest.optJSONObject("startup");
            if (phase != null && phase.has("phase_elapsed_seconds")) information += String.format(Locale.ROOT,
                    "\nStage: %.0f s · limit %.0f s", phase.optDouble("phase_elapsed_seconds"), phase.optDouble("phase_budget_seconds"));
        }
        ToolUi.setText(detail, information == null ? "" : information);
        detail.setTextColor(failed ? ERR : MUTED);
        String label = running ? "Stop environment" : "Start environment";
        if (!label.equals(lastStart)) {
            lastStart = label;
            start.setText(label);
            if (running) {
                start.setTextColor(ERR);
                start.setBackground(outlined(this, ERR_SOFT, 10, Theme.withAlpha(ERR, 0x40), 1));
            } else {
                start.setTextColor(ON_ACCENT);
                start.setBackground(rounded(this, ACCENT, 10));
            }
        }
        boolean configuring = running || stopping;
        ToolUi.setEnabled(ram, !configuring);
        ToolUi.setEnabled(cores, !configuring);
        ToolUi.setEnabled(start, !stopping);
        JSONArray list = vm.guest.optJSONArray("tasks");
        String value = list == null ? "[]" : list.toString();
        if (!value.equals(lastTasks)) {
            boolean first = lastTasks == null;
            lastTasks = value;
            renderTasks(list, first);
        }
        if (!exportsLoading) {
            exportsLoading = true;
            new Thread(() -> {
                JSONArray saved = vm.exports();
                runOnUiThread(() -> { exportsLoading = false; if (!isDestroyed()) renderExports(saved); });
            }, "docker-exports").start();
        }
    }

    private void renderTasks(JSONArray list, boolean first) {
        tasks.removeAllViews();
        if (list == null || list.length() == 0) {
            TextView empty = text(this, "Your tasks will appear here.", 14, MUTED);
            empty.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
            tasks.addView(empty, full());
            return;
        }
        int delay = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject task = list.optJSONObject(i);
            if (task == null) continue;
            String id = task.optString("id");
            JSONArray jobs = task.optJSONArray("jobs");
            JSONObject job = jobs == null || jobs.length() == 0 ? null : jobs.optJSONObject(jobs.length() - 1);
            String jobState = job == null ? "" : job.optString("state");
            String meta = "#" + (id.length() > 6 ? id.substring(0, 6) : id) + (job == null ? " · no runs"
                    : " · " + kind(job.optString("kind")) + " " + state(jobState));
            ToolUi.Row r = new ToolUi.Row(this, R.drawable.ms_terminal, task.optString("name", "Task"), meta, true)
                    .onClick(v -> startActivity(new Intent(this, DockerTaskActivity.class).putExtra("task", id)));
            if (job != null) {
                Dot d = toolDot(this, "running".equals(jobState) || "queued".equals(jobState) ? ACCENT
                        : "succeeded".equals(jobState) ? OK : "failed".equals(jobState) ? ERR : FAINT);
                d.setPulsing("running".equals(jobState) || "queued".equals(jobState));
                r.trail(d);
            }
            String url = task.optString("url", ""), lan = task.isNull("lan_url") ? "" : task.optString("lan_url", "");
            int port = task.optInt("host_port", 0);
            if (!url.isEmpty() && port > 0) {
                // Each task publishes its own phone port: tap opens the preview, long press copies.
                LinearLayout links = row(this);
                links.addView(ToolUi.urlChip(this, url, ACCENT_TEXT, () -> startActivity(
                        DockerPreviewActivity.intent(this, port, task.optString("name")))), wrap());
                if (!lan.isEmpty()) {
                    TextView wifi = ToolUi.urlChip(this, lan, INFO, null);
                    wifi.setContentDescription("Wi-Fi address " + lan);
                    LinearLayout.LayoutParams wp = wrap();
                    wp.leftMargin = dp(this, 6);
                    links.addView(wifi, wp);
                }
                r.extra(links, 8);
            } else if (task.has("preview_error")) {
                r.setMeta(meta + " · preview: " + task.optString("preview_error"));
            }
            if (tasks.getChildCount() > 0) tasks.addView(hairline(), dividerParams());
            tasks.addView(r.view, full());
            if (!first && seenTasks.add(id)) Motion.enter(r.view, delay += 40);
            else seenTasks.add(id);
        }
    }

    private static String kind(String k) {
        switch (k) {
            case "build": return "building";
            case "run": return "run";
            case "exec": return "command";
            default: return k;
        }
    }

    private static String state(String s) {
        switch (s) {
            case "running": return "running";
            case "queued": return "queued";
            case "succeeded": return "ready";
            case "failed": return "error";
            case "cancelled": return "canceled";
            default: return s;
        }
    }

    private void renderExports(JSONArray saved) {
        String files = saved.toString();
        if (files.equals(lastExports)) return;
        boolean first = lastExports == null;
        lastExports = files;
        exports.removeAllViews();
        exports.setBackground(saved.length() == 0 ? outlined(this, 0x00000000, 14, OUTLINE, 1) : outlined(this, CARD, 14, LINE, 1));
        if (saved.length() == 0) {
            TextView empty = text(this, "Export a file before deleting the task.", 14, MUTED);
            empty.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
            exports.addView(empty, full());
            return;
        }
        int delay = 0;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject file = saved.optJSONObject(i);
            if (file == null) continue;
            String task = file.optString("task");
            String meta = ToolUi.size(file.optLong("bytes")) + (task.isEmpty() ? "" : " · task #" + (task.length() > 6 ? task.substring(0, 6) : task));
            ToolUi.Row r = new ToolUi.Row(this, R.drawable.ms_description, file.optString("name"), meta, true)
                    .onClick(v -> openExport(file));
            ToolUi.add(exports, r.view);
            String id = file.optString("id");
            if (!first && seenExports.add(id)) Motion.enter(r.view, delay += 40);
            else seenExports.add(id);
        }
    }

    void openExport(JSONObject file) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(file.optString("uri"))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String name = file.optString("name");
        view.setDataAndType(Uri.parse(file.optString("uri")), name.endsWith(".apk") ? "application/vnd.android.package-archive" : name.endsWith(".html") ? "text/html" : "application/octet-stream");
        try { startActivity(view); } catch (Exception e) {
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, Uri.parse(file.optString("uri"))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(share, "Save or share file")); } catch (Exception x) { error(x); }
        }
    }

    private void showLog() {
        LinearLayout content = column(this);
        String console = vm.console();
        TextView log = ToolUi.logPanel(content, console.isEmpty() ? "You can keep the log from the previous run with the “Save boot diagnostics” button." : "", ToolUi.screenDp(this, 0.55f), true);
        if (!console.isEmpty()) log.setText(AnsiText.render(console, mono(this, 700)));
        Button close = button(this, "Close", false);
        content.addView(close, ToolUi.buttonParams(this, 14));
        Dialog d = ToolUi.sheet(this, "Linux log", null, content);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void showLicenses() {
        new Thread(() -> {
            try (java.io.InputStream in = getAssets().open("docker/SOURCES.txt"); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                final String content = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    LinearLayout box = column(this);
                    TextView text = ToolUi.logPanel(box, content, ToolUi.screenDp(this, 0.55f), false);
                    android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS);
                    Button close = button(this, "Close", false);
                    box.addView(close, ToolUi.buttonParams(this, 14));
                    Dialog d = ToolUi.sheet(this, "Environment licenses", null, box);
                    close.setOnClickListener(v -> d.dismiss());
                    d.show();
                });
            } catch (Exception e) { runOnUiThread(() -> error(e)); }
        }, "docker-licenses").start();
    }

    private void error(Exception e) {
        if (!isDestroyed()) ToolUi.message(this, "Docker", String.valueOf(e.getMessage()), false, true);
    }

    @Override protected void onResume() { super.onResume(); poll.removeCallbacks(refresh); poll.post(refresh); }
    @Override protected void onPause() { poll.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putInt("ram", ram.getSelectedItemPosition()); out.putInt("cores", cores.getSelectedItemPosition()); super.onSaveInstanceState(out); }
}
