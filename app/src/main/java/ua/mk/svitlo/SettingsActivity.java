package ua.mk.svitlo;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/** Everything that isn't needed every day: addresses, notifications, sharing, widget help. */
public class SettingsActivity extends AppCompatActivity {
    private static final int[] LEADS = {10, 15, 30, 60};
    private static final int[] CHIPS = {R.id.chip_10, R.id.chip_15, R.id.chip_30, R.id.chip_60};
    private static final int[] INTERVALS = {5, 10, 15, 30};
    private static final int[] INTERVAL_CHIPS = {R.id.chip_i5, R.id.chip_i10, R.id.chip_i15, R.id.chip_i30};

    private LinearLayout addrList;
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
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        addrList = findViewById(R.id.addr_list);
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
        ((TextView) findViewById(R.id.m_version)).setText("Версія " + AppUpdate.installedName(this));

        Store st = new Store(this);
        bindSwitch(R.id.sw_before, Store.F_BEFORE, st);
        bindSwitch(R.id.sw_return, Store.F_RETURN, st);
        bindSwitch(R.id.sw_emergency, Store.F_EMERGENCY, st);
        bindSwitch(R.id.sw_schedule, Store.F_SCHEDULE, st);

        ChipGroup leads = findViewById(R.id.cg_lead);
        int checked = R.id.chip_30;
        for (int i = 0; i < LEADS.length; i++) if (LEADS[i] == st.lead()) checked = CHIPS[i];
        leads.check(checked);
        leads.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            for (int i = 0; i < CHIPS.length; i++) {
                if (CHIPS[i] == ids.get(0)) {
                    Store s = new Store(this);
                    if (s.lead() != LEADS[i]) {
                        s.setLead(LEADS[i]);
                        runTick();
                    }
                }
            }
        });

        ChipGroup intervals = findViewById(R.id.cg_interval);
        int checkedInterval = R.id.chip_i10;
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == st.interval()) checkedInterval = INTERVAL_CHIPS[i];
        intervals.check(checkedInterval);
        intervals.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            for (int i = 0; i < INTERVAL_CHIPS.length; i++) {
                if (INTERVAL_CHIPS[i] == ids.get(0)) {
                    Store s = new Store(this);
                    if (s.interval() != INTERVALS[i]) {
                        s.setInterval(INTERVALS[i]);
                        runTick();
                    }
                }
            }
        });
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
        Store st = new Store(this);
        Address active = st.active();
        addrList.removeAllViews();
        List<Address> list = st.addresses();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Додайте свою адресу, щоб бачити графік і отримувати сповіщення.");
            empty.setTextSize(16);
            empty.setTextColor(MaterialColors.getColor(addrList, com.google.android.material.R.attr.colorOnSurfaceVariant));
            int p = dp(16);
            empty.setPadding(p, p, p, p);
            addrList.addView(empty);
            return;
        }
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < list.size(); i++) {
            Address a = list.get(i);
            boolean isActive = active != null && active.domId == a.domId;
            if (i > 0) addrList.addView(new MaterialDivider(this));

            View row = inf.inflate(R.layout.item_address, addrList, false);
            row.findViewById(R.id.a_check).setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
            ((TextView) row.findViewById(R.id.a_title)).setText(a.name);
            ((TextView) row.findViewById(R.id.a_sub)).setText(a.full + "\n" + a.queuesText());

            MaterialSwitch bell = row.findViewById(R.id.a_notify);
            bell.setChecked(a.notify);
            bell.setOnCheckedChangeListener((b, isChecked) -> {
                Store s = new Store(this);
                List<Address> all = s.addresses();
                for (Address x : all) if (x.domId == a.domId) x.notify = isChecked;
                s.saveAddresses(all);
                runTick();
            });

            ImageButton del = row.findViewById(R.id.a_delete);
            del.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle("Видалити адресу?")
                    .setMessage(a.name + "\n" + a.full)
                    .setPositiveButton("Видалити", (d, w) -> {
                        new Store(this).removeAddress(a.domId);
                        renderAddresses();
                        runTick();
                    })
                    .setNegativeButton("Скасувати", null)
                    .show());

            row.setOnClickListener(v -> {
                new Store(this).setActiveDom(a.domId);
                renderAddresses();
                runTick();
            });
            addrList.addView(row);
        }
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
