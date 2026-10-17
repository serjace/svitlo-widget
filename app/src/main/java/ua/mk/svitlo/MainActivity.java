package ua.mk.svitlo;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int[] LEADS = {10, 15, 30, 60};
    private static final int[] CHIPS = {R.id.chip_10, R.id.chip_15, R.id.chip_30, R.id.chip_60};
    private static final int MAX_ROWS = 5;

    private TextView mAddress, mStatus, mDetail, mTodayLabel, mNextLabel, mTomorrowLabel,
            mTomorrowText, mQueues, mUpdated, mBatteryHint;
    private ImageView mIcon;
    private HourGridView mTodayGrid, mTomorrowGrid;
    private View mSchedule;
    private LinearLayout mNext, addrList;
    private MaterialButton btnRefresh, btnBattery;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAddress = findViewById(R.id.m_address);
        mIcon = findViewById(R.id.m_icon);
        mStatus = findViewById(R.id.m_status);
        mDetail = findViewById(R.id.m_detail);
        mSchedule = findViewById(R.id.m_schedule);
        mTodayLabel = findViewById(R.id.m_today_label);
        mTodayGrid = findViewById(R.id.m_today_grid);
        mNextLabel = findViewById(R.id.m_next_label);
        mNext = findViewById(R.id.m_next);
        mTomorrowLabel = findViewById(R.id.m_tomorrow_label);
        mTomorrowGrid = findViewById(R.id.m_tomorrow_grid);
        mTomorrowText = findViewById(R.id.m_tomorrow_text);
        mQueues = findViewById(R.id.m_queues);
        mUpdated = findViewById(R.id.m_updated);
        mBatteryHint = findViewById(R.id.m_battery_hint);
        addrList = findViewById(R.id.addr_list);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnBattery = findViewById(R.id.btn_battery);

        ((TextView) findViewById(R.id.m_legend)).setText(legend());

        btnRefresh.setOnClickListener(v -> refresh());
        findViewById(R.id.btn_add).setOnClickListener(v ->
                startActivity(new Intent(this, AddAddressActivity.class)));
        findViewById(R.id.btn_site).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Api.BASE + "/")));
            } catch (ActivityNotFoundException ignored) {
            }
        });
        btnBattery.setOnClickListener(v -> askBattery());

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

        Notifier.createChannels(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.setListener(this::render);
        render();
        updateBatteryButton();
        Store st = new Store(this);
        if (!st.addresses().isEmpty() && System.currentTimeMillis() - st.fetchedAt() > 5 * 60 * 1000L) {
            refresh();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Updater.setListener(null);
    }

    private void bindSwitch(int id, String key, Store st) {
        MaterialSwitch sw = findViewById(id);
        sw.setChecked(st.flag(key));
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            new Store(this).setFlag(key, isChecked);
            runTick();
        });
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        btnRefresh.setEnabled(false);
        mUpdated.setText("Оновлення…");
        new Thread(() -> {
            Updater.tick(getApplicationContext(), true);
            runOnUiThread(() -> {
                refreshing = false;
                btnRefresh.setEnabled(true);
                render();
            });
        }).start();
    }

    private void runTick() {
        new Thread(() -> Updater.tick(getApplicationContext(), false)).start();
    }

    private void render() {
        Store st = new Store(this);
        Schedule sch = Schedule.load(st);
        ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
        Address a = st.active();
        Status s = Status.compute(sch, a, now);

        mAddress.setText(a == null ? "Адресу ще не додано" : a.name);
        mIcon.setBackgroundResource(Timeline.circleRes(s.level));
        mIcon.setImageResource(Timeline.iconRes(s.level));
        mStatus.setText(s.title);
        mDetail.setText(s.detail);

        if (a != null && sch.hasData) {
            mSchedule.setVisibility(View.VISIBLE);
            LocalDate today = now.toLocalDate();
            LocalDate tomorrow = today.plusDays(1);
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");

            int[] t = a.gpvId > 0 ? sch.slots(today, a.gpvId) : null;
            if (t == null) t = new int[48];
            mTodayLabel.setText("Сьогодні, " + today.format(f) + " — по годинах");
            mTodayGrid.setData(t, now.getHour());

            mNext.removeAllViews();
            LayoutInflater inf = getLayoutInflater();
            for (int k = 0; k < s.next.size() && k < MAX_ROWS; k++) {
                int[] r = s.next.get(k);
                View row = inf.inflate(R.layout.item_period, mNext, false);
                ((ImageView) row.findViewById(R.id.p_pill)).setImageResource(Timeline.pillRes(r[2]));
                ((TextView) row.findViewById(R.id.p_time)).setText(Status.rowTime(r, s.nowIdx));
                ((TextView) row.findViewById(R.id.p_label)).setText(Status.rowLabel(r));
                mNext.addView(row);
            }
            mNextLabel.setVisibility(s.next.isEmpty() ? View.GONE : View.VISIBLE);

            mTomorrowLabel.setText("Завтра, " + tomorrow.format(f));
            int[] m = null;
            if (sch.hasDay(tomorrow)) m = a.gpvId > 0 ? sch.slots(tomorrow, a.gpvId) : new int[48];
            if (m != null) {
                mTomorrowGrid.setVisibility(View.VISIBLE);
                mTomorrowGrid.setData(m, -1);
                mTomorrowText.setText(Status.describeDay(m));
            } else {
                mTomorrowGrid.setVisibility(View.GONE);
                mTomorrowText.setText("Графік на завтра ще не опубліковано");
            }
            mQueues.setText("Черги: " + a.queuesText() + "\n" + a.full);
        } else {
            mSchedule.setVisibility(View.GONE);
        }
        if (!refreshing) mUpdated.setText(st.updatedLabel());

        renderAddresses(st, a);
    }

    private void renderAddresses(Store st, Address active) {
        addrList.removeAllViews();
        List<Address> list = st.addresses();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Додайте свою адресу, щоб бачити графік і отримувати сповіщення.");
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
                        render();
                        runTick();
                    })
                    .setNegativeButton("Скасувати", null)
                    .show());

            row.setOnClickListener(v -> {
                new Store(this).setActiveDom(a.domId);
                render();
                runTick();
            });
            addrList.addView(row);
        }
    }

    private CharSequence legend() {
        SpannableStringBuilder b = new SpannableStringBuilder();
        addLegend(b, Timeline.ON);
        addLegend(b, Timeline.MAYBE);
        addLegend(b, Timeline.OFF);
        return b;
    }

    private void addLegend(SpannableStringBuilder b, int lvl) {
        int start = b.length();
        b.append("■ ");
        b.setSpan(new ForegroundColorSpan(getColor(Timeline.strongColor(lvl))), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(Timeline.name(lvl).toLowerCase()).append("    ");
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
