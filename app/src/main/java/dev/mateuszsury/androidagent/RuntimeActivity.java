package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Installation is automatic; account authentication stays with the device owner. */
public final class RuntimeActivity extends Screen {
    private static final String[] BACKENDS = {"codex", "claude"}, NAMES = {"Codex", "Claude Code"};
    private static final String[] META = {
            "openai · 0.162.1 · ~155 MB",
            "anthropic · 2.1.296 · ~108 MB",
    };
    private EmbeddedRuntime runtime;
    private TextView state, details, percent;
    private Dot stateDot;
    private ToolUi.Meter progress;
    private LinearLayout progressRow;
    private Button prepare, cancel;
    private ToolUi.Row terminal, files;
    private final Button[] installs = new Button[2], logins = new Button[2];
    private final TextView[] badges = new TextView[2];
    private final CheckBox[] picks = new CheckBox[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() {
            render();
            handler.postDelayed(this, 750);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        runtime = EmbeddedRuntime.get(this);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.addView(topBar(this, "Environment and accounts", "~/runtime · bash · python · node · git"), full());
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = ToolUi.body(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView intro = ToolUi.note(this, "Terminal, Python, Node.js, Git and the tool bridge are in this app. The environment "
                + "sets itself up automatically. Official clients download separately over the internet; you sign in with your own account.", 14);
        intro.setPadding(dp(this, 20), dp(this, 10), dp(this, 20), dp(this, 14));
        body.addView(intro, full());

        // ------------------------------------------------------------ hero: environment status
        LinearLayout hero = terminalBox(this, "environment", ACCENT);
        LinearLayout head = row(this);
        head.setGravity(Gravity.TOP);
        stateDot = toolDot(this, FAINT);
        LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16));
        dotP.topMargin = dp(this, 3);
        head.addView(stateDot, dotP);
        state = title(this, "", 16);
        state.setPadding(dp(this, 8), 0, 0, 0);
        head.addView(state, weight1());
        hero.addView(head, full());
        progressRow = row(this);
        progress = new ToolUi.Meter(this, ACCENT);
        progressRow.addView(progress, new LinearLayout.LayoutParams(0, dp(this, 6), 1));
        percent = monoText(this, "", 12, ACCENT_TEXT);
        percent.setTypeface(mono(this, 600));
        percent.setGravity(Gravity.END);
        percent.setMinWidth(dp(this, 44));
        progressRow.addView(percent, wrap());
        LinearLayout.LayoutParams pp = full();
        pp.topMargin = dp(this, 12);
        hero.addView(progressRow, pp);
        details = text(this, "", 13, MUTED);
        details.setLineSpacing(dp(this, 2), 1f);
        details.setPadding(0, dp(this, 10), 0, 0);
        hero.addView(details, full());
        prepare = button(this, "Set up selected agents", true);
        prepare.setOnClickListener(v -> {
            try { runtime.prepare(); } catch (Exception e) { error(e); }
            render();
        });
        hero.addView(prepare, ToolUi.buttonParams(this, 14));
        cancel = button(this, "Cancel setup", false);
        cancel.setOnClickListener(v -> {
            runtime.cancelSetup();
            render();
        });
        hero.addView(cancel, ToolUi.buttonParams(this, 8));
        LinearLayout.LayoutParams heroP = cardParams(this);
        heroP.topMargin = dp(this, 4);
        body.addView(hero, heroP);

        // ------------------------------------------------------------ clients
        body.addView(sectionLabel(this, "Agent clients"), full());
        String wanted = Prefs.agents(this);
        for (int i = 0; i < BACKENDS.length; i++) {
            final int index = i;
            final String backend = BACKENDS[i];
            LinearLayout card = card(this);
            LinearLayout top = row(this);
            top.addView(agentTile(this, backend));
            LinearLayout texts = column(this);
            texts.setPadding(dp(this, 12), 0, dp(this, 8), 0);
            TextView name = single(title(this, NAMES[i], 16));
            texts.addView(name);
            TextView meta = single(monoText(this, META[i], 11, FAINT));
            meta.setPadding(0, dp(this, 3), 0, 0);
            texts.addView(meta);
            top.addView(texts, weight1());
            badges[i] = badge(this, "", FAINT);
            top.addView(badges[i], wrap());
            card.addView(top, full());
            picks[i] = ToolUi.checkBox(this, "Include in setup");
            picks[i].setTextColor(MUTED);
            picks[i].setTextSize(13);
            picks[i].setChecked("both".equals(wanted) || backend.equals(wanted));
            picks[i].setOnCheckedChangeListener((b, on) -> pick(index, on));
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = dp(this, 8);
            card.addView(picks[i], cp);
            installs[i] = button(this, "Install " + NAMES[i], false);
            installs[i].setOnClickListener(v -> {
                try { runtime.installClient(backend); } catch (Exception e) { error(e); }
                render();
            });
            logins[i] = button(this, "Sign in to " + NAMES[i], false);
            logins[i].setOnClickListener(v -> {
                try { TerminalActivity.openLogin(this, backend); } catch (Exception e) { error(e); }
            });
            // One action at a time: install first, then the official login.
            card.addView(installs[i], ToolUi.buttonParams(this, 8));
            card.addView(logins[i], ToolUi.buttonParams(this, 8));
            body.addView(card, cardParams(this));
        }

        // ------------------------------------------------------------ tools
        body.addView(sectionLabel(this, "Tools"), full());
        LinearLayout tools = ToolUi.group(this);
        terminal = new ToolUi.Row(this, R.drawable.ms_terminal, "Open terminal", "bash · full session with keyboard", true)
                .onClick(v -> TerminalActivity.openShell(this));
        files = new ToolUi.Row(this, R.drawable.ms_folder, "Environment files", "~/ · projects and home directory", true)
                .onClick(v -> startActivity(new Intent(this, RuntimeFilesActivity.class)));
        ToolUi.Row permissions = new ToolUi.Row(this, R.drawable.ms_shield_person, "Phone permissions", "Android permissions for agents", true)
                .onClick(v -> PermissionGuide.open(this));
        ToolUi.Row log = new ToolUi.Row(this, R.drawable.ms_menu, "Setup log", "setup.log", true)
                .onClick(v -> showLog());
        ToolUi.Row sources = new ToolUi.Row(this, R.drawable.ms_description, "Environment sources and licenses", "SOURCES.txt", true)
                .onClick(v -> showSources());
        ToolUi.add(tools, terminal.view);
        ToolUi.add(tools, files.view);
        ToolUi.add(tools, permissions.view);
        ToolUi.add(tools, log.view);
        ToolUi.add(tools, sources.view);
        body.addView(tools, cardParams(this));
        TextView foot = ToolUi.note(this, "Automatic setup does not sign you in to your account. You grant system permissions in "
                + "Android settings. The terminal runs with androidagent's permissions; there is no root.", 12);
        foot.setTextColor(FAINT);
        foot.setPadding(dp(this, 20), dp(this, 6), dp(this, 20), 0);
        body.addView(foot, full());
        setContentView(root);
        render();
        Motion.stagger(body, 30, 35);
    }

    /** Keep at least one client selected; "both" when both are checked. */
    private void pick(int index, boolean on) {
        boolean codex = picks[0].isChecked(), claude = picks[1].isChecked();
        if (!codex && !claude) {
            picks[index].setChecked(true);
            Motion.shake(picks[index]);
            Toast.makeText(this, "Select at least one agent", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.setAgents(this, codex && claude ? "both" : codex ? "codex" : "claude");
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacksAndMessages(null);
        handler.post(refresh);
        if (!runtime.ready() && !runtime.busy() && runtime.error().isEmpty()) runtime.bootstrap();
        else if (runtime.ready()) withToolHost(() -> runtime.refreshAccounts());
    }

    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void render() {
        boolean busy = runtime.busy(), ready = runtime.ready();
        String error = runtime.error();
        ToolUi.setText(state, runtime.status());
        int color = !error.isEmpty() ? ERR : busy ? ACCENT : ready ? OK : FAINT;
        stateDot.setColor(color);
        stateDot.setPulsing(busy);
        ToolUi.setText(details, error.isEmpty()
                ? (busy ? "You can return to your chats. Setup continues in the background." : "Terminal: " + (ready ? "ready" : "setup required"))
                : error);
        details.setTextColor(error.isEmpty() ? MUTED : ERR);
        int value = runtime.progress();
        progress.set(busy ? Math.max(0.02f, value / 100f) : 0f, ACCENT);
        ToolUi.setText(percent, value + "%");
        ToolUi.setVisible(progressRow, busy);
        ToolUi.setEnabled(prepare, !busy);
        ToolUi.setText(prepare, error.isEmpty() ? "Set up selected agents" : "Retry setup");
        ToolUi.setVisible(cancel, busy);
        for (int i = 0; i < 2; i++) {
            boolean installed = runtime.agentInstalled(BACKENDS[i]);
            boolean signed = installed && SetupState.agentReady(this, BACKENDS[i]);
            ToolUi.setVisible(installs[i], !installed);
            ToolUi.setVisible(logins[i], installed);
            ToolUi.setEnabled(installs[i], ready && !busy && !installed);
            ToolUi.setText(installs[i], installed ? "Client installed" : "Install " + NAMES[i]);
            ToolUi.setEnabled(logins[i], installed && !busy);
            ToolUi.setText(logins[i], signed ? "Open sign-in · account connected" : "Sign in to " + NAMES[i]);
            int badgeColor = signed ? OK : installed ? ACCENT_TEXT : FAINT;
            ToolUi.setText(badges[i], signed ? "connected" : installed ? "installed" : "not installed");
            if (badges[i].getCurrentTextColor() != badgeColor) {
                badges[i].setTextColor(badgeColor);
                badges[i].setBackground(rounded(this, Theme.withAlpha(badgeColor, 0x22), 6));
            }
        }
        terminal.setEnabled(ready);
        files.setEnabled(ready);
    }

    private void error(Exception e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }

    private void showLog() {
        LinearLayout content = column(this);
        String log = runtime.setupLog();
        ToolUi.logPanel(content, log.isEmpty() ? "No entries." : log, ToolUi.screenDp(this, 0.55f), true);
        Button close = button(this, "Close", false);
        content.addView(close, ToolUi.buttonParams(this, 14));
        Dialog d = ToolUi.sheet(this, "Environment setup", "Log of the latest setup steps.", content);
        close.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void showSources() {
        try (java.io.InputStream input = getAssets().open("terminal/SOURCES.txt")) {
            java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (content.size() + count > 256 * 1024) throw new java.io.IOException("Sources document exceeds the limit");
                content.write(buffer, 0, count);
            }
            LinearLayout box = column(this);
            TextView text = ToolUi.logPanel(box, new String(content.toByteArray(), java.nio.charset.StandardCharsets.UTF_8),
                    ToolUi.screenDp(this, 0.55f), false);
            Linkify.addLinks(text, Linkify.WEB_URLS);
            Button close = button(this, "Close", false);
            box.addView(close, ToolUi.buttonParams(this, 14));
            Dialog d = ToolUi.sheet(this, "Sources and licenses", null, box);
            close.setOnClickListener(v -> d.dismiss());
            d.show();
        } catch (Exception exception) {
            error(exception);
        }
    }
}
