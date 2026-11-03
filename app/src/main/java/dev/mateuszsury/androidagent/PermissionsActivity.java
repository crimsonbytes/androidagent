package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Optional Android consents; opening this screen never requests or grants a permission. */
public class PermissionsActivity extends Screen {
    private static final int MICROPHONE_REQUEST = 903, LOCATION_REQUEST = 906, PHOTOS_REQUEST = 907;
    private static final String PREFERENCES = "permission_guide";
    private LinearLayout cards;
    private ScrollView scroll;
    private TextView subtitle;
    private boolean entered;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout bar = topBar(this, "Permissions", "Android permissions");
        subtitle = (TextView) ((LinearLayout) ((LinearLayout) bar.getChildAt(0)).getChildAt(1)).getChildAt(1);
        root.addView(bar, full());
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        cards = column(this);
        cards.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 28));
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    // The explicit buttons below own notification consent on this screen.
    @Override protected void offerNotifications() {}

    @Override protected void onNotificationPermissionChanged() { refresh(); }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE_REQUEST || request == LOCATION_REQUEST || request == PHOTOS_REQUEST) refresh();
    }

    private void refresh() {
        if (cards == null || isDestroyed()) return;
        int position = scroll.getScrollY();
        PermissionGuide.State state = PermissionGuide.read(this);
        subtitle.setText(state.enabledCount() + " of " + PermissionGuide.State.TOTAL + " enabled · Android permissions");
        cards.removeAllViews();
        paragraph(cards,
                "The terminal and chats work without screen or microphone access. "
                        + "Signing in to the model account is a separate step. "
                        + "You turn on permissions yourself in Android dialogs.", MUTED);

        section("App");
        card(R.drawable.ms_notifications, "Notifications", state.notifications ? "On" : "Off · optional",
                state.notifications ? OK : FAINT,
                "They show active tasks and a stop button. Declining does not block chats or tools. "
                        + "Android may still show an active service in its panel.",
                state.notifications ? "Notification settings" : "Enable notifications",
                () -> {
                    if (NotificationPermission.canRequest(this)) NotificationPermission.requestIfNeeded(this);
                    else launch(NotificationPermission.settingsIntent(this), PermissionGuide.appSettingsIntent(this));
                }, null);
        card(R.drawable.ms_mic, "Microphone", state.microphone ? "Allowed" : "Off · optional",
                state.microphone ? OK : FAINT,
                "Needed only for dictation. You can always type on the keyboard. "
                        + "Dictation also requires a speech recognition service on the phone.",
                state.microphone ? "Microphone settings" : "Allow microphone",
                () -> requestRuntime("microphone_requested", new String[]{Manifest.permission.RECORD_AUDIO},
                        MICROPHONE_REQUEST, state.microphone), null);
        String accessibilityStatus = state.accessibilityConnected ? "Connected"
                : state.accessibilityEnabled ? "On · waiting for connection" : "Off · optional";
        card(R.drawable.ms_touch_app, "Screen control", accessibilityStatus,
                state.accessibilityConnected ? OK : state.accessibilityEnabled ? WARN : FAINT,
                "The accessibility service lets the agent read the screen, tap, and scroll. "
                        + "In settings, select androidagent and confirm. "
                        + "Other accessibility services remain under your control.",
                "Accessibility settings",
                () -> launch(PermissionGuide.accessibilitySettingsIntent(), PermissionGuide.appSettingsIntent(this)),
                state.accessibilityEnabled ? null : restrictedNote(
                        "If Android blocks this permission after an APK install, open App info "
                                + "and in the three-dot menu choose Allow restricted settings, if available. "
                                + "Then return to accessibility settings."));
        card(R.drawable.ms_battery_charging_full, "Long tasks and battery",
                state.batteryExempt ? "No battery optimization" : "Optimization active", state.batteryExempt ? OK : FAINT,
                "For long chats, builds and the Docker VM, you can let the app run in the background. "
                        + "Android or the device maker may still stop the process when memory is low. "
                        + "If you have problems, also check background restrictions in App info.",
                state.batteryExempt ? "Battery settings" : "Allow background activity",
                () -> {
                    if (state.batteryExempt) launch(PermissionGuide.batteryListIntent(), PermissionGuide.appSettingsIntent(this));
                    else launch(PermissionGuide.batterySettingsIntent(this), PermissionGuide.batteryListIntent());
                }, null);

        section("Agent tools");
        String access = state.notificationAccessConnected ? "On"
                : state.notificationAccess ? "On · waiting for connection" : "Off · optional";
        card(R.drawable.ms_notifications_active, "Notification access", access,
                state.notificationAccessConnected ? OK : state.notificationAccess ? WARN : FAINT,
                "The agent can read the title and content of notifications, open them, press their button, or dismiss them, "
                        + "when you ask in chat. Notification content is never treated as a command by the agent.",
                state.notificationAccess ? "Notification access settings" : "Enable notification access",
                () -> launch(PermissionGuide.notificationAccessIntent(this), PermissionGuide.notificationAccessListIntent()),
                state.notificationAccess || Build.VERSION.SDK_INT < 33 ? null : restrictedNote(
                        "Android may mark this setting as restricted for apps installed from an APK file. "
                                + "In that case, in App info, open the three-dot menu and choose "
                                + "Allow restricted settings."));
        boolean located = state.locationCoarse;
        card(R.drawable.ms_location_on, "Location",
                state.locationFine ? "Precise" : located ? "Approximate" : "Off · optional", located ? OK : FAINT,
                "The agent checks the phone's location when you ask, e.g., for the weather or a nearby route. "
                        + "Works only while the app is open. Android will ask whether to share precise or approximate location.",
                located ? "Location settings" : "Allow location",
                () -> requestRuntime("location_requested", new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST, located), null);
        card(R.drawable.ms_photo_library, "Photos", state.photos ? "Allowed" : "Off · optional",
                state.photos ? OK : FAINT,
                "The agent can browse the latest gallery photos and copy selected ones to its working directory. "
                        + "Taking photos with the camera doesn't need this permission: you always take the photo yourself.",
                state.photos ? "Photo settings" : "Allow photo access",
                () -> requestRuntime("photos_requested", new String[]{MediaTools.photoPermission()}, PHOTOS_REQUEST,
                        state.photos), null);
        card(R.drawable.ms_install_mobile, "Install apps", state.installApps ? "Allowed" : "Off · optional",
                state.installApps ? OK : FAINT,
                "Lets the agent open the Android installer for apps it builds. "
                        + "You confirm every install and update yourself in an Android dialog.",
                state.installApps ? "Install settings" : "Allow installing",
                () -> launch(PermissionGuide.installAppsIntent(this), PermissionGuide.appSettingsIntent(this)), null);
        card(R.drawable.ms_settings, "Change system settings", state.writeSettings ? "Allowed" : "Off · optional",
                state.writeSettings ? OK : FAINT,
                "Needed only by tools that change things like screen brightness. "
                        + "It does not grant access to passwords or to settings protected by Android.",
                state.writeSettings ? "App system settings" : "Allow changing settings",
                () -> launch(PermissionGuide.writeSettingsIntent(this), PermissionGuide.appSettingsIntent(this)), null);

        TextView footer = paragraph(cards,
                "Files and USB: permission is requested only after you choose a folder or a specific device. "
                        + "You do not need to share the entire phone storage. "
                        + "The terminal and chats need neither root nor permission to install other apps.", FAINT);
        footer.setPadding(dp(this, 4), dp(this, 10), dp(this, 4), 0);
        if (!entered) {
            entered = true;
            Motion.stagger(cards, 0, 28);
        }
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    /** Asks Android once (and again only while it offers a rationale); afterwards opens App info. */
    private void requestRuntime(String key, String[] permissions, int request, boolean granted) {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        boolean requested = preferences.getBoolean(key, false);
        if (!granted && (!requested || shouldShowRequestPermissionRationale(permissions[0]))) {
            preferences.edit().putBoolean(key, true).apply();
            try { requestPermissions(permissions, request); }
            catch (RuntimeException e) { launch(PermissionGuide.appSettingsIntent(this), null); }
        } else launch(PermissionGuide.appSettingsIntent(this), null);
    }

    private void launch(Intent preferred, Intent fallback) {
        try { startActivity(preferred); }
        catch (RuntimeException e) {
            if (fallback != null) {
                try { startActivity(fallback); return; }
                catch (RuntimeException ignored) {}
            }
            Toast.makeText(this, "These settings are unavailable. Open androidagent app info in Android settings.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private Button actionButton(String label, boolean emphasized) {
        Button action = emphasized ? outlinedButton(this, label, ACCENT_TEXT) : button(this, label, false);
        action.setSingleLine(false);
        action.setEllipsize(null);
        action.setMinHeight(dp(this, 44));
        action.setMinimumHeight(dp(this, 44));
        return action;
    }

    private void section(String label) {
        TextView t = sectionLabel(this, label);
        t.setPadding(dp(this, 4), dp(this, 16), dp(this, 4), dp(this, 8));
        cards.addView(t, full());
    }

    private void card(int icon, String name, String status, int tone, String explanation, String label, Runnable action,
                      View extra) {
        boolean active = tone == OK;
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        LinearLayout head = row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(this, tone == FAINT ? CARD2 : Theme.withAlpha(tone, 0x24), 12));
        ImageView glyph = Ui.icon(this, icon, tone == FAINT ? MUTED : tone, 22);
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(tile, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42)));
        LinearLayout texts = column(this);
        TextView heading = title(this, name, 16);
        heading.setAccessibilityHeading(true);
        texts.addView(heading, full());
        LinearLayout pill = statusPill(this, status, tone);
        TextView current = (TextView) pill.getChildAt(1);
        current.setSingleLine(false);
        current.setMaxLines(2);
        current.setEllipsize(null);
        current.setTextColor(tone == FAINT ? MUTED : TEXT);
        current.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams pillParams = wrap();
        pillParams.topMargin = dp(this, 6);
        texts.addView(pill, pillParams);
        LinearLayout.LayoutParams textParams = weight1();
        textParams.leftMargin = dp(this, 14);
        head.addView(texts, textParams);
        card.addView(head, full());
        TextView about = paragraph(card, explanation, MUTED);
        about.setPadding(0, dp(this, 12), 0, 0);
        Button button = actionButton(label, !active);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams buttonParams = full();
        buttonParams.topMargin = dp(this, 14);
        card.addView(button, buttonParams);
        if (extra != null) {
            LinearLayout.LayoutParams extraParams = full();
            extraParams.topMargin = dp(this, 12);
            card.addView(extra, extraParams);
        }
        LinearLayout.LayoutParams params = full();
        params.bottomMargin = dp(this, 10);
        cards.addView(card, params);
    }

    /** A quiet inset for Android's "restricted settings" step, with a route to App info. */
    private View restrictedNote(String text) {
        LinearLayout box = column(this);
        box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 12));
        box.setBackground(outlined(this, Theme.withAlpha(WARN, 0x10), 10, Theme.withAlpha(WARN, 0x30), 1));
        TextView note = text(this, text, 12, MUTED);
        note.setLineSpacing(dp(this, 2), 1);
        box.addView(note, full());
        Button details = actionButton("App info", false);
        details.setTextSize(13);
        details.setOnClickListener(v -> launch(PermissionGuide.appSettingsIntent(this), null));
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = dp(this, 10);
        box.addView(details, params);
        return box;
    }

    private TextView paragraph(LinearLayout parent, String value, int color) {
        TextView description = text(this, value, 13, color);
        description.setLineSpacing(dp(this, 3), 1);
        description.setPadding(dp(this, 4), 0, dp(this, 4), dp(this, 4));
        parent.addView(description, full());
        return description;
    }
}
