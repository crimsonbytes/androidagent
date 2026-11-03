package dev.mateuszsury.androidagent;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static dev.mateuszsury.androidagent.Ui.*;

/** Settings: embedded environment, account state and the existing appearance preferences. */
public class SetupActivity extends Screen {
    private static final String[][] MODES = {{"system", "System"}, {"light", "Light"}, {"dark", "Dark"}};
    private static final String[][] SURFACES = {{"warm", "Warm"}, {"neutral", "Neutral"}, {"black", "Black"}};
    /** Polish names for {@link Theme#ACCENTS}, by hex. */
    private static final String[][] ACCENT_NAMES = {
            {"#D97757", "Coral"}, {"#4F8BFF", "Ocean"}, {"#3FB98A", "Mint"},
            {"#9A7CFF", "Violet"}, {"#E5678F", "Rose"}, {"#E0A43A", "Amber"},
    };

    private ScrollView scroll;
    private LinearLayout list;
    /** Setup card open state; null = automatic (open until all steps are done). */
    private Boolean setupOpen;
    private final android.os.Handler callbacks = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Everything the page shows, as of the last render; a 1 s watch re-renders only when it changes. */
    private String rendered;
    private boolean entered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private void build() {
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        root.addView(RootHeader.build(this, "Settings", "environment · account · appearance"), full());
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = column(this);
        list.setPadding(0, dp(this, 4), 0, dp(this, 28));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomNav(this, NAV_SETTINGS, ChatHub.get(this).runningCount()), full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        refresh();
        callbacks.removeCallbacks(watchRuntime);
        callbacks.postDelayed(watchRuntime, 1000);
        ping();
    }

    @Override protected void onNotificationPermissionChanged() { refresh(); }

    @Override protected void onPause() {
        callbacks.removeCallbacksAndMessages(null);
        super.onPause();
    }

    /** All inputs of {@link #render}: when none changed, the page is left untouched (no flicker). */
    private String state() {
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        PowerManager pm = getSystemService(PowerManager.class);
        return runtime.ready() + ":" + runtime.busy() + ":" + runtime.status() + ":"
                + runtime.progress() + ":" + runtime.error() + ":" + Prefs.helperCheckedAt(this) + ":"
                + Prefs.signedAgents(this) + ":" + Prefs.backend(this) + ":" + Prefs.agents(this) + ":"
                + SetupState.agentInstalled(this, "claude") + SetupState.agentInstalled(this, "codex") + ":"
                + SetupState.accessibilityEnabled(this) + ":"
                + (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) + ":"
                + NotificationPermission.enabled(this) + ":" + Prefs.hadReply(this) + ":" + Prefs.devMode(this) + ":"
                + Prefs.bubbleEnabled(this) + ":" + Prefs.speakReplies(this) + ":" + setupOpen;
    }

    private void refresh() {
        if (!state().equals(rendered)) render();
    }

    private final Runnable watchRuntime = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            refresh();
            callbacks.postDelayed(this, 1000);
        }
    };

    /** Checks the installed clients' actual account status without displaying credentials. */
    private void ping() {
        if (!EmbeddedRuntime.get(this).ready()) return;
        withToolHost(() -> {
            EmbeddedRuntime.get(this).refreshAccounts();
            callbacks.postDelayed(this::refresh, 1200);
            callbacks.postDelayed(this::refresh, 4100);
        });
    }

    /** Appearance changed on this screen: restyle everything in place, keeping the scroll position. */
    private void themeChanged() {
        Theme.changed(this);
        rethemed();
        int y = scroll.getScrollY();
        build();
        entered = true;
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private static final class Step {
        String title, body, action, action2;
        Boolean done;
        boolean optional;
        View.OnClickListener l, l2;
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        rendered = state();
        int scrollY = scroll.getScrollY();
        list.removeAllViews();

        TextView intro = text(this, "Codex with a ChatGPT account or Claude Code with a Claude account. The official clients run "
                + "in the built-in environment on this phone.", 14, MUTED);
        intro.setLineSpacing(dp(this, 2), 1f);
        intro.setPadding(dp(this, 20), dp(this, 14), dp(this, 20), dp(this, 2));
        list.addView(intro, full());

        // ---------------------------------------------------------------- setup
        EmbeddedRuntime runtime = EmbeddedRuntime.get(this);
        boolean a11y = SetupState.accessibilityEnabled(this);
        boolean hasClaude = SetupState.agentInstalled(this, "claude");
        boolean hasCodex = SetupState.agentInstalled(this, "codex");
        String backend = Prefs.backend(this);
        String agent = "codex".equals(backend) ? "Codex" : "Claude Code";
        boolean agentInstalled = SetupState.agentInstalled(this, backend);
        boolean accountReady = SetupState.agentReady(this, backend);
        Step[] steps = new Step[5];

        String environment = runtime.ready()
                ? "Terminal, files and the tool bridge are available in androidagent. You don't need a separate Termux app."
                : runtime.busy() ? runtime.status() + " · " + runtime.progress() + "%"
                : "androidagent sets up its own terminal environment and the official clients. Downloading the clients requires internet.";
        if (runtime.error() != null && !runtime.error().isEmpty()) environment = runtime.error();
        steps[0] = step("Built-in environment", environment, runtime.ready(),
                runtime.ready() ? "Open environment" : runtime.busy() ? "Show progress" : "Set up / retry",
                v -> EmbeddedRuntime.open(this), null, null);

        String account = accountReady ? agent + " is installed and signed in."
                : !agentInstalled ? "First set up client " + agent + " in the environment panel."
                : "Client " + agent + " is ready. Sign in to your own account through the official website. Installing the environment does not sign you in automatically.";
        steps[1] = step("Account " + agent, account, accountReady,
                accountReady ? "Check account" : agentInstalled ? "Sign in" : "Set up client",
                v -> {
                    if (accountReady) ping();
                    else if (agentInstalled) TermuxSetup.signIn(this, backend);
                    else EmbeddedRuntime.open(this);
                }, agentInstalled && !accountReady ? "Check again" : null, v -> ping());

        String a11yText = "Lets the agent read the screen, tap and type text in other apps. Chats, the terminal and Docker work without this permission.";
        if (!a11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            a11yText += " Android may first require allowing restricted settings in App info.";
        steps[2] = step("Screen control", a11yText, a11y,
                "Review permissions", v -> PermissionGuide.open(this), null, null);
        steps[2].optional = true;

        PowerManager pm = getSystemService(PowerManager.class);
        boolean background = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        steps[3] = step("Background work",
                "A battery optimization exemption helps keep long tasks running. Android requires your approval; the setting applies only to androidagent.",
                background, "Review permissions", v -> PermissionGuide.open(this), null, null);
        steps[3].optional = true;

        steps[4] = step("First chat", Prefs.hadReply(this)
                        ? "Chat history is saved. You can return to it from the home screen."
                        : "After signing in, start a chat. /status reads phone status locally and works without a model.",
                Prefs.hadReply(this), "Open chats", v -> startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)), null, null);
        steps[4].optional = true;
        setupCard(steps);

        // -------------------------------------------------------- notifications
        list.addView(sectionLabel(this, "Notifications"));
        boolean notify = NotificationPermission.enabled(this);
        LinearLayout notifications = card(this);
        notifications.addView(statusTitle(notify ? "App notifications on" : "App notifications off",
                notify ? OK : FAINT), full());
        TextView nb = text(this, "They show agent, USB and Docker activity plus a Stop button. "
                + "Permission is optional — features also work without notifications.", 13, MUTED);
        nb.setPadding(dp(this, 22), 0, 0, 0);
        notifications.addView(nb, topGap(4));
        Button notificationSettings = button(this, "Notification settings", false);
        notificationSettings.setOnClickListener(v -> startActivity(NotificationPermission.settingsIntent(this)));
        notifications.addView(notificationSettings, buttonGap(12));
        addCard(notifications);

        // ----------------------------------------------------------- appearance
        list.addView(sectionLabel(this, "Appearance"));
        LinearLayout ap = card(this);
        ap.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ap.addView(fieldLabel("Mode", null));
        ap.addView(segmented(this, MODES, Prefs.themeMode(this), false, v -> {
            Prefs.setThemeMode(this, v);
            themeChanged();
        }), topGap(8));
        int accent = Prefs.accent(this);
        String accentName = accentName(accent);
        ap.addView(fieldLabel("Accent", (accentName == null ? "custom" : accentName.toLowerCase(java.util.Locale.ROOT))
                + " · " + Theme.hex(accent)), topGap(20));
        ap.addView(swatches(), topGap(10));
        ap.addView(fieldLabel("Surface", null), topGap(20));
        ap.addView(segmented(this, SURFACES, Prefs.surface(this), false, v -> {
            Prefs.setSurface(this, v);
            themeChanged();
        }), topGap(8));
        TextView sn = text(this, "Warm is the original androidagent tone. Black (true black) works only in dark mode.",
                13, MUTED);
        ap.addView(sn, topGap(10));
        addCard(ap);

        // ---------------------------------------------------------------- agent
        list.addView(sectionLabel(this, "Agent"));
        LinearLayout ag = card(this);
        ag.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ag.addView(title(this, "Default agent", 15));
        TextView brainInfo = text(this, "New chats will use the selected agent. Claude Code uses your Claude account, and Codex uses your ChatGPT account.", 13, MUTED);
        brainInfo.setPadding(0, dp(this, 4), 0, 0);
        ag.addView(brainInfo);
        TextView clients = monoText(this, "available clients: " + (hasClaude && hasCodex ? "claude-code, codex"
                : hasClaude ? "claude-code" : hasCodex ? "codex" : "not set up yet"), 11, FAINT);
        clients.setPadding(0, dp(this, 6), 0, 0);
        ag.addView(clients);
        ag.addView(segmented(this, new String[][]{{"claude", "Claude Code"}, {"codex", "Codex"}}, backend, false, v -> {
            Prefs.setBackend(this, v);
            if (!"both".equals(Prefs.agents(this))) Prefs.setAgents(this, v);
            Toast.makeText(this, "New chats will use: " + ("codex".equals(v) ? "Codex" : "Claude Code")
                    + ". You can also change the agent on the home screen.", Toast.LENGTH_LONG).show();
            render();
        }), topGap(12));
        addCard(ag);

        // ----------------------------------------------------------- experience
        list.addView(sectionLabel(this, "Experience"));
        LinearLayout ex = card(this);
        ex.setPadding(0, dp(this, 2), 0, dp(this, 2));
        ex.addView(switchRow(this, "Chat bubble", "Floats over other apps. Drag it onto the close button "
                + "to hide it; approval cards will still appear when the agent needs your approval.",
                Prefs.bubbleEnabled(this), on -> {
            Prefs.setBubbleEnabled(this, on);
            if (on) HiddenNotice.cancel(this);
            BuddyService svc = BuddyService.get();
            if (svc != null) svc.applyBubbleSetting();
            rendered = state();
        }), full());
        ex.addView(divider(this), lineParams());
        ex.addView(switchRow(this, "Read replies aloud", "Talk using the microphone in any chat; your message "
                + "is sent when you stop speaking.", Prefs.speakReplies(this), on -> {
            Prefs.setSpeakReplies(this, on);
            if (!on) Voice.stopSpeaking();
            rendered = state();
        }), full());
        ex.addView(divider(this), lineParams());
        final boolean dev = Prefs.devMode(this);
        ex.addView(switchRow(this, "Developer mode", "Codex and Claude Code can run commands and change files that androidagent can access. "
                + "Starting directory: ~/.buddy/work. Access also covers other files available to the app.", dev, on -> {
            Prefs.setDevMode(this, on);
            render();
        }), full());
        if (dev) {
            LinearLayout warn = row(this);
            warn.setGravity(Gravity.TOP);
            warn.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            warn.setBackground(outlined(this, ERR_SOFT, 10, Theme.withAlpha(ERR, 0x40), 1));
            warn.addView(icon(this, R.drawable.ms_warning, ERR, 18));
            TextView wt = text(this, "Agent commands in the built-in environment run without separate confirmation. "
                    + "Android limits them to androidagent's permissions; they are not confined to the project directory. "
                    + "Commands from the run_command phone tool require approval of their exact text.", 13, ERR);
            wt.setPadding(dp(this, 10), 0, 0, 0);
            warn.addView(wt, weight1());
            LinearLayout.LayoutParams wp = full();
            wp.setMargins(dp(this, 12), 0, dp(this, 12), dp(this, 12));
            ex.addView(warn, wp);
            if (!entered) Motion.enter(warn, 0);
        }
        addCard(ex);

        // ------------------------------------------------------- remote control
        list.addView(sectionLabel(this, "Claude remote continuation"));
        LinearLayout rc = card(this);
        TextView rct = text(this, "The phone button in a Claude Code chat opens this session in the built-in terminal "
                + "with Remote Control. You can continue it in the official Claude app. Availability depends on your Claude account.", 14, MUTED);
        rct.setLineSpacing(dp(this, 2), 1f);
        rc.addView(rct);
        addCard(rc);

        list.addView(sectionLabel(this, "Agent tools"));
        LinearLayout pt = card(this);
        pt.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        TextView ptt = text(this, "The tool bridge is configured automatically for androidagent chats. "
                + "Find the terminal, files and official sign-in in the environment panel. Android permissions stay under your control.", 14, MUTED);
        ptt.setLineSpacing(dp(this, 2), 1f);
        pt.addView(ptt);
        Button runtimeButton = button(this, "Open environment and clients", false);
        runtimeButton.setOnClickListener(v -> EmbeddedRuntime.open(this));
        pt.addView(runtimeButton, fullButton(14));
        Button permissionsButton = button(this, "Review permissions", false);
        permissionsButton.setOnClickListener(v -> PermissionGuide.open(this));
        pt.addView(permissionsButton, fullButton(8));
        addCard(pt);

        TextView ver = monoText(this, "androidagent " + BuildInfo.VERSION + " · " + getPackageName(), 11, FAINT);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(this, 26), 0, 0);
        list.addView(ver, full());
        if (!entered) {
            entered = true;
            Motion.stagger(list, 0, 30);
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    // ------------------------------------------------------------- setup card

    private Step step(String title, String body, Boolean done, String action, View.OnClickListener l,
                      String action2, View.OnClickListener l2) {
        Step s = new Step();
        s.title = title;
        s.body = body;
        s.done = done;
        s.action = action;
        s.l = l;
        s.action2 = action2;
        s.l2 = l2;
        return s;
    }

    private void setupCard(Step[] steps) {
        int done = 0, next = -1, required = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i].optional) continue;
            required++;
            if (Boolean.TRUE.equals(steps[i].done)) done++;
            else if (next < 0) next = i;
        }
        boolean complete = next < 0;
        boolean open = setupOpen != null ? setupOpen : !complete;

        LinearLayout c = card(this);
        c.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        LinearLayout head = row(this);
        LinearLayout titles = column(this);
        TextView ht = title(this, "Setup", 16);
        ht.setAccessibilityHeading(true);
        titles.addView(ht);
        TextView hs = monoText(this, complete ? "all set" : "required: " + done + " of " + required, 11, FAINT);
        hs.setPadding(0, dp(this, 2), 0, 0);
        titles.addView(hs);
        head.addView(titles, weight1());
        head.addView(badge(this, done + "/" + required, complete ? OK : ACCENT_TEXT));
        ImageView chevron = icon(this, R.drawable.ms_expand_more, MUTED, 22);
        chevron.setRotation(open ? 180 : 0);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22));
        chp.leftMargin = dp(this, 8);
        head.addView(chevron, chp);
        head.setContentDescription("Setup, " + done + " of " + required + (open ? ", collapse" : ", expand"));
        head.setOnClickListener(v -> {
            Motion.rotate(chevron, open ? 0 : 180);
            setupOpen = !open;
            chevron.postDelayed(this::render, Motion.enabled() ? 140 : 0);
        });
        c.addView(head, full());

        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(rounded(this, CARD2, 2));
        View fill = new View(this);
        fill.setBackground(rounded(this, complete ? OK : ACCENT, 2));
        bar.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        final int doneF = done;
        final int requiredF = Math.max(1, required);
        final boolean animate = !entered;
        bar.post(() -> {
            int target = bar.getWidth() * doneF / requiredF;
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            if (animate && Motion.enabled() && target > 0) {
                ValueAnimator a = ValueAnimator.ofInt(0, target);
                a.setDuration(Motion.LONG + 200);
                a.setStartDelay(160);
                a.setInterpolator(Motion.EASE_OUT);
                a.addUpdateListener(an -> {
                    fp.width = (Integer) an.getAnimatedValue();
                    fill.setLayoutParams(fp);
                });
                a.start();
            } else {
                fp.width = target;
                fill.setLayoutParams(fp);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 4));
        bp.topMargin = dp(this, 12);
        c.addView(bar, bp);

        if (!open) {
            LinearLayout r = row(this);
            r.setPadding(0, dp(this, 12), 0, 0);
            if (complete) r.addView(toolDot(this, OK));
            else r.addView(new Spark(this).animated(false).color(ACCENT), new LinearLayout.LayoutParams(dp(this, 14), dp(this, 14)));
            TextView s = single(text(this, complete ? "Environment and account are ready." : "Next step: " + steps[next].title, 13, MUTED));
            s.setPadding(dp(this, 8), 0, 0, 0);
            r.addView(s, weight1());
            c.addView(r, full());
        } else {
            for (int i = 0; i < steps.length; i++) c.addView(stepRow(steps[i], i, i == next), full());
        }
        if (!complete) {
            TextView guide = monoText(this, "Open guide →", 13, ACCENT_TEXT);
            guide.setTypeface(mono(this, 600));
            guide.setPadding(0, dp(this, 10), dp(this, 8), dp(this, 2));
            guide.setBackgroundResource(ripple(this, false));
            guide.setOnClickListener(v -> startActivity(new Intent(this, OnboardingActivity.class)));
            LinearLayout.LayoutParams gp = wrap();
            gp.topMargin = dp(this, 6);
            c.addView(guide, gp);
        }
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 14), dp(this, 16), 0);
        list.addView(c, p);
    }

    /** One checklist line: OK dot (done) · breathing spark (current) · faint dot (pending), mono number, title. */
    private View stepRow(Step st, int index, boolean isNext) {
        boolean isDone = Boolean.TRUE.equals(st.done);
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(this, 14), 0, 0);
        FrameLayout marker = new FrameLayout(this);
        if (isNext) {
            Spark spark = new Spark(this).color(ACCENT);
            marker.addView(spark, new FrameLayout.LayoutParams(dp(this, 14), dp(this, 14), Gravity.CENTER));
        } else {
            Ui.Dot d = new Ui.Dot(this, isDone ? OK : st.optional ? Theme.withAlpha(FAINT, 0xA0) : FAINT);
            marker.addView(d, new FrameLayout.LayoutParams(dp(this, 14), dp(this, 14), Gravity.CENTER));
        }
        r.addView(marker, new LinearLayout.LayoutParams(dp(this, 16), dp(this, 20)));
        TextView num = monoText(this, String.valueOf(index + 1), 12, isNext ? ACCENT_TEXT : FAINT);
        num.setTypeface(mono(this, 600));
        num.setGravity(Gravity.CENTER);
        r.addView(num, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 20)));
        LinearLayout col = column(this);
        col.setPadding(dp(this, 4), 0, 0, 0);
        LinearLayout tr = row(this);
        TextView t = single(title(this, st.title, 15));
        t.setTextColor(isNext ? TEXT : isDone ? TEXT : MUTED);
        if (!isNext) t.setTypeface(sans(this, 500));
        tr.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (isDone) {
            tr.addView(monoText(this, "ready", 11, OK));
        } else if (st.optional) {
            tr.addView(monoText(this, "optional", 11, FAINT));
        }
        col.addView(tr, full());
        boolean expanded = isNext || (st.optional && !isDone);
        if (expanded) {
            TextView b = text(this, st.body, 13, MUTED);
            b.setLineSpacing(dp(this, 2), 1f);
            b.setPadding(0, dp(this, 4), 0, 0);
            col.addView(b);
            if (st.action != null || st.action2 != null) {
                LinearLayout btns = column(this);
                if (st.action != null) {
                    Button a = button(this, st.action, isNext);
                    a.setOnClickListener(st.l);
                    btns.addView(a, wrap());
                }
                if (st.action2 != null) {
                    Button a2 = button(this, st.action2, false);
                    a2.setOnClickListener(st.l2);
                    LinearLayout.LayoutParams a2p = wrap();
                    if (st.action != null) a2p.topMargin = dp(this, 8);
                    btns.addView(a2, a2p);
                }
                LinearLayout.LayoutParams bt = full();
                bt.topMargin = dp(this, 10);
                col.addView(btns, bt);
            }
        }
        r.addView(col, weight1());
        return r;
    }

    // ----------------------------------------------------------- appearance

    private View fieldLabel(String s, String right) {
        LinearLayout r = row(this);
        r.addView(title(this, s, 15), weight1());
        if (right != null) r.addView(monoText(this, right, 11, FAINT));
        return r;
    }

    private static String accentName(int color) {
        for (String[] a : ACCENT_NAMES) if (Theme.parse(a[0], 0) == color) return a[1];
        return null;
    }

    private View swatches() {
        LinearLayout r = row(this);
        int current = Prefs.accent(this);
        boolean preset = false;
        for (String[] a : Theme.ACCENTS) {
            int col = Theme.parse(a[0], 0xFFD97757);
            boolean on = col == current;
            preset |= on;
            String name = accentName(col);
            r.addView(swatch(col, on, false, (name == null ? a[1] : name) + (on ? ", selected" : ""), v -> {
                if (on) return;
                Prefs.setAccent(this, col);
                themeChanged();
            }), new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        }
        r.addView(swatch(preset ? CARD2 : current, !preset, preset, "Custom color" + (!preset ? ", selected" : ""),
                v -> customAccent()), new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        return r;
    }

    /** A colour circle; the selected one gets a ring and a check that pop in. */
    private View swatch(int color, boolean on, boolean plus, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setContentDescription(desc);
        View circle = new View(this);
        circle.setBackground(plus ? outlined(this, CARD2, 16, OUTLINE, 1) : oval(color));
        f.addView(circle, new FrameLayout.LayoutParams(dp(this, 30), dp(this, 30), Gravity.CENTER));
        if (plus) {
            ImageView ic = icon(this, R.drawable.ms_add, MUTED, 18);
            f.addView(ic, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        }
        if (on) {
            View ring = new View(this);
            ring.setBackground(outlined(this, 0x00000000, 22, TEXT, 2));
            f.addView(ring, new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.CENTER));
            int checkColor = Theme.luminance(color) > 0.55 ? 0xFF1A1714 : 0xFFFFFFFF;
            ImageView check = icon(this, R.drawable.ms_check, checkColor, 18);
            f.addView(check, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
            Motion.pop(ring, 80);
            Motion.pop(check, 140);
        }
        f.setBackgroundResource(ripple(this, true));
        f.setOnClickListener(l);
        return f;
    }

    private void customAccent() {
        LinearLayout c = column(this);
        TextView heading = title(this, "Custom accent", 20);
        heading.setAccessibilityHeading(true);
        c.addView(heading);
        TextView s = text(this, "Any color as a hex code, e.g. #4F8BFF.", 13, MUTED);
        s.setPadding(0, dp(this, 4), 0, dp(this, 14));
        c.addView(s);
        LinearLayout r = row(this);
        final View preview = new View(this);
        preview.setBackground(oval(Prefs.accent(this)));
        r.addView(preview, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        final EditText et = input(this, "#RRGGBB", true);
        et.setText(Theme.hex(Prefs.accent(this)));
        et.setSingleLine(true);
        et.setContentDescription("Hex color code");
        LinearLayout.LayoutParams ep = weight1();
        ep.leftMargin = dp(this, 12);
        r.addView(et, ep);
        Button apply = button(this, "Apply", true);
        LinearLayout.LayoutParams app = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 46));
        app.leftMargin = dp(this, 8);
        r.addView(apply, app);
        c.addView(r, full());
        TextView sw = monoText(this, "preset colors", 11, FAINT);
        sw.setPadding(0, dp(this, 16), 0, dp(this, 6));
        c.addView(sw);
        LinearLayout presets = row(this);
        for (String[] a : Theme.ACCENTS) {
            int col = Theme.parse(a[0], 0xFFD97757);
            View dotV = new View(this);
            dotV.setBackground(oval(col));
            FrameLayout slot = new FrameLayout(this);
            slot.addView(dotV, new FrameLayout.LayoutParams(dp(this, 24), dp(this, 24), Gravity.CENTER));
            slot.setBackgroundResource(ripple(this, true));
            String name = accentName(col);
            slot.setContentDescription(name == null ? a[1] : name);
            slot.setOnClickListener(v -> {
                et.setText(Theme.hex(col));
                et.setSelection(et.getText().length());
            });
            presets.addView(slot, new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        }
        c.addView(presets, full());
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                int col = valid(e.toString());
                preview.setBackground(oval(col != 0 ? col : CARD2));
                apply.setEnabled(col != 0);
                apply.setAlpha(col != 0 ? 1f : 0.4f);
            }
        });
        final Dialog d = bottomSheet(this, c);
        apply.setOnClickListener(v -> {
            int col = valid(et.getText().toString());
            if (col == 0) {
                Motion.shake(et);
                return;
            }
            d.dismiss();
            Prefs.setAccent(this, col);
            themeChanged();
        });
        d.show();
    }

    private static int valid(String text) {
        String h = text.trim();
        if (h.replace("#", "").length() != 6) return 0;
        return Theme.parse(h, 0);
    }

    // ---------------------------------------------------------------- helpers

    /** A card title with a state dot in front. */
    private View statusTitle(String s, int color) {
        LinearLayout r = row(this);
        Ui.Dot d = toolDot(this, color);
        r.addView(d);
        TextView t = title(this, s, 15);
        t.setPadding(dp(this, 6), 0, 0, 0);
        r.addView(t, weight1());
        return r;
    }

    private void addCard(View c) {
        list.addView(c, cardParams(this));
    }

    private LinearLayout.LayoutParams lineParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        p.leftMargin = p.rightMargin = dp(this, 16);
        return p;
    }

    private LinearLayout.LayoutParams topGap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }

    private LinearLayout.LayoutParams buttonGap(int dpv) {
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(this, dpv);
        p.leftMargin = dp(this, 22);
        return p;
    }

    private LinearLayout.LayoutParams fullButton(int dpv) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 44));
        p.topMargin = dp(this, dpv);
        return p;
    }
}
