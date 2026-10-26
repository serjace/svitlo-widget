package ua.mk.svitlo;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything that isn't needed every day: addresses, notifications, sharing, widget help. */
public class SettingsActivity extends AppCompatActivity {
    private static final int[] LEADS = {10, 15, 30};
    private static final int[] CHIPS = {R.id.chip_10, R.id.chip_15, R.id.chip_30};
    private static final int[] INTERVALS = {5, 10, 15, 30};
    private static final int[] INTERVAL_BUTTONS = {R.id.btn_int_5, R.id.btn_int_10, R.id.btn_int_15, R.id.btn_int_30};
    private static final float[] SCALES = {0.85f, 1f, 1.15f, 1.3f};
    private static final String[] SCALE_NAMES = {"Менший", "Звичайний", "Більший", "Найбільший"};

    private RecyclerView addrList;
    private TextView addrEmpty;
    private final AddrAdapter addrAdapter = new AddrAdapter();
    private ItemTouchHelper dragger;
    private boolean moved;
    private TextView mBatteryHint;
    private MaterialButton btnBattery;

    /** Opens the share sheet (Viber, Telegram, SMS…) with a link and short instructions. */
    static void share(Context ctx) {
        String text = "Застосунок «Світло» показує, чи є світло за вашою адресою і коли його вимкнуть "
                + "(графік Миколаївобленерго), і має віджет на головний екран.\n\n"
                + "Як встановити:\n"
                + "1. Відкрийте на телефоні посилання: https://github.com/serjace/svitlo-widget/releases/latest\n"
                + "2. Натисніть svitlo.apk і завантажте.\n"
                + "3. Відкрийте завантажений файл і натисніть «Встановити» "
                + "(якщо телефон попросить — дозвольте встановлення з цього джерела).\n"
                + "4. Відкрийте «Світло» і додайте свою адресу.";
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Застосунок «Світло»")
                .putExtra(Intent.EXTRA_TEXT, text);
        try {
            ctx.startActivity(Intent.createChooser(send, "Поділитися застосунком"));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        View content = findViewById(R.id.settings_content);
        Bars.apply(this, findViewById(R.id.settings_root), bottom -> Bars.padBottom(content, bottom));
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        addrList = findViewById(R.id.addr_list);
        addrEmpty = findViewById(R.id.addr_empty);
        addrList.setLayoutManager(new LinearLayoutManager(this));
        addrList.setAdapter(addrAdapter);
        MaterialDividerItemDecoration div = new MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL);
        div.setLastItemDecorated(false);
        addrList.addItemDecoration(div);
        // Hold ≡ (or the whole row) and drag to change the order
        dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a < 0 || b < 0) return false;
                Collections.swap(addrAdapter.items, a, b);
                addrAdapter.notifyItemMoved(a, b);
                moved = true;
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder h, int state) {
                super.onSelectedChanged(h, state);
                if (h != null && state == ItemTouchHelper.ACTION_STATE_DRAG) h.itemView.setAlpha(0.85f);
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                super.clearView(rv, h);
                h.itemView.setAlpha(1f);
                if (moved) {
                    moved = false;
                    saveOrder();
                }
            }
        });
        dragger.attachToRecyclerView(addrList);
        mBatteryHint = findViewById(R.id.m_battery_hint);
        btnBattery = findViewById(R.id.btn_battery);

        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, AddAddressActivity.class)));
        findViewById(R.id.btn_share).setOnClickListener(v -> share(this));
        findViewById(R.id.btn_site).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Api.BASE + "/")));
            } catch (ActivityNotFoundException ignored) {
            }
        });
        btnBattery.setOnClickListener(v -> askBattery());

        // Xiaomi / Redmi / POCO (MIUI) block background work and opening the app from a widget
        // unless "Автозапуск" and "pop-up windows in background" are allowed
        if (isXiaomi()) {
            View autostart = findViewById(R.id.btn_autostart);
            autostart.setVisibility(View.VISIBLE);
            autostart.setOnClickListener(v -> openAutostart(this));
            View popup = findViewById(R.id.btn_miui_popup);
            popup.setVisibility(View.VISIBLE);
            popup.setOnClickListener(v -> openMiuiPermissions(this));
        }
        ((TextView) findViewById(R.id.m_version)).setText("Версія " + AppUpdate.installedName(this));

        Store st = new Store(this);
        bindSwitch(R.id.sw_before, Store.F_BEFORE, st);
        bindSwitch(R.id.sw_return, Store.F_RETURN, st);
        bindSwitch(R.id.sw_emergency, Store.F_EMERGENCY, st);
        bindSwitch(R.id.sw_schedule, Store.F_SCHEDULE, st);

        // Widget text size: drop-down list
        MaterialAutoCompleteTextView ws = findViewById(R.id.ac_wscale);
        ws.setSimpleItems(SCALE_NAMES);
        int wsIdx = 1;
        for (int i = 0; i < SCALES.length; i++) if (Math.abs(SCALES[i] - st.widgetScale()) < 0.01f) wsIdx = i;
        ws.setText(SCALE_NAMES[wsIdx], false);
        ws.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= SCALES.length) return;
            Store s = new Store(this);
            if (Math.abs(s.widgetScale() - SCALES[position]) > 0.01f) {
                s.setWidgetScale(SCALES[position]);
                runTick();
            }
        });

        // Warning lead time
        MaterialButtonToggleGroup leads = findViewById(R.id.tg_lead);
        int checked = R.id.chip_10;
        for (int i = 0; i < LEADS.length; i++) if (LEADS[i] == st.lead()) checked = CHIPS[i];
        leads.check(checked);
        leads.addOnButtonCheckedListener((group, id, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < CHIPS.length; i++) {
                if (CHIPS[i] == id) {
                    Store s = new Store(this);
                    if (s.lead() != LEADS[i]) {
                        s.setLead(LEADS[i]);
                        runTick();
                    }
                }
            }
        });

        // Automatic checks: 5, 10, 15 or 30 minutes (default).
        MaterialButtonToggleGroup interval = findViewById(R.id.tg_interval);
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == st.interval()) interval.check(INTERVAL_BUTTONS[i]);
        }
        interval.addOnButtonCheckedListener((group, id, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < INTERVALS.length; i++) {
                if (id != INTERVAL_BUTTONS[i]) continue;
                Store s = new Store(this);
                if (s.interval() != INTERVALS[i]) {
                    s.setInterval(INTERVALS[i]);
                    new Thread(() -> Updater.tickDue(getApplicationContext())).start();
                }
                break;
            }
        });
    }

    /** Dialog to change an address's name ("Дім", "Батьки"…). */
    static void rename(AppCompatActivity act, long domId, Runnable done) {
        Address a = new Store(act).find(domId);
        if (a == null) return;
        View v = act.getLayoutInflater().inflate(R.layout.dialog_rename, null);
        TextInputEditText et = v.findViewById(R.id.et_rename);
        et.setText(a.name);
        et.setSelection(a.name.length());
        new MaterialAlertDialogBuilder(act)
                .setTitle("Назва адреси")
                .setView(v)
                .setPositiveButton("Зберегти", (d, w) -> {
                    String n = et.getText() == null ? "" : et.getText().toString().trim();
                    if (n.isEmpty()) return;
                    Store s = new Store(act);
                    List<Address> all = s.addresses();
                    for (Address x : all) if (x.domId == domId) x.name = n;
                    s.saveAddresses(all);
                    new Thread(() -> Updater.tick(act.getApplicationContext(), false)).start();
                    done.run();
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAddresses();
        updateBatteryButton();
    }

    private void bindSwitch(int id, String key, Store st) {
        MaterialSwitch sw = findViewById(id);
        sw.setChecked(st.flag(key));
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            new Store(this).setFlag(key, isChecked);
            runTick();
        });
    }

    private void runTick() {
        new Thread(() -> Updater.tick(getApplicationContext(), false)).start();
    }

    private void renderAddresses() {
        List<Address> list = new Store(this).addresses();
        addrAdapter.items.clear();
        addrAdapter.items.addAll(list);
        addrAdapter.notifyDataSetChanged();
        addrEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        addrList.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Address, queues and — only when limited — the notification hours. */
    private static String subText(Address a) {
        String s = a.full + "\n" + a.queuesText();
        String hours = a.notifyHoursText();
        if (a.notify && !hours.isEmpty()) s += "\n🔔 " + hours;
        return s;
    }

    /** Compact rows: ≡ · name and address (tap = edit or delete) · 🔔 switch. */
    private class AddrAdapter extends RecyclerView.Adapter<AddrAdapter.Row> {
        final List<Address> items = new ArrayList<>();

        class Row extends RecyclerView.ViewHolder {
            final TextView title, sub;
            final MaterialSwitch bell;

            @SuppressLint("ClickableViewAccessibility")
            Row(View v) {
                super(v);
                title = v.findViewById(R.id.a_title);
                sub = v.findViewById(R.id.a_sub);
                bell = v.findViewById(R.id.a_notify);
                // touching ≡ starts dragging right away; a long press on the row works too
                v.findViewById(R.id.a_handle).setOnTouchListener((h, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN && items.size() > 1) dragger.startDrag(this);
                    return false;
                });
            }
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(getLayoutInflater().inflate(R.layout.item_address, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int position) {
            Address a = items.get(position);
            h.title.setText(a.name);
            h.sub.setText(subText(a));
            h.itemView.findViewById(R.id.a_handle).setVisibility(items.size() > 1 ? View.VISIBLE : View.INVISIBLE);
            h.bell.setOnCheckedChangeListener(null);
            h.bell.setChecked(a.notify);
            h.bell.setOnCheckedChangeListener((b, isChecked) -> {
                a.notify = isChecked;
                h.sub.setText(subText(a));
                Store s = new Store(SettingsActivity.this);
                List<Address> all = s.addresses();
                for (Address x : all) if (x.domId == a.domId) x.notify = isChecked;
                s.saveAddresses(all);
                runTick();
            });
            // Tap = edit name and address (delete is there too)
            h.itemView.setOnClickListener(v -> startActivity(new Intent(SettingsActivity.this, AddAddressActivity.class)
                    .putExtra(AddAddressActivity.EXTRA_REPLACE, a.domId)));
            h.itemView.setOnLongClickListener(v -> {
                if (items.size() > 1) dragger.startDrag(h);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** Saves the order after a drag (main-screen buttons and the widget ⇄ follow it). */
    private void saveOrder() {
        Store s = new Store(this);
        List<Address> all = s.addresses();
        List<Address> ordered = new ArrayList<>();
        for (Address x : addrAdapter.items) {
            for (Address y : all) if (y.domId == x.domId) ordered.add(y);
        }
        if (ordered.size() != all.size()) return;
        s.saveAddresses(ordered);
        runTick();
    }

    private void updateBatteryButton() {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        btnBattery.setVisibility(ok ? View.GONE : View.VISIBLE);
        mBatteryHint.setText(ok ? "✓ Роботу у фоні дозволено — сповіщення приходитимуть вчасно."
                : "Щоб сповіщення приходили вчасно, дозвольте застосунку працювати у фоні.");
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    static boolean isXiaomi() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
    }

    private static void openAppDetails(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /** MIUI "Автозапуск" list. */
    static void openAutostart(Context ctx) {
        try {
            ctx.startActivity(new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));
        } catch (Exception e) {
            openAppDetails(ctx);
        }
    }

    /** MIUI "Інші дозволи" for this app, where "pop-up windows in background" lives. */
    static void openMiuiPermissions(Context ctx) {
        try {
            ctx.startActivity(new Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", ctx.getPackageName()));
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter",
                                "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                        .putExtra("extra_pkgname", ctx.getPackageName()));
            } catch (Exception e2) {
                openAppDetails(ctx);
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
