package ua.mk.svitlo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class MainActivity extends Activity {
    private static final int[] LEADS = {10, 15, 30, 60};

    private TextView mAddress, mDot, mStatus, mDetail, mTodayLabel, mTodayText,
            mTomorrowLabel, mTomorrowText, mQueues, mUpdated, mBatteryHint;
    private ImageView mTodayImg, mTomorrowImg;
    private View mSchedule;
    private LinearLayout addrList;
    private Button btnRefresh, btnBattery;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAddress = findViewById(R.id.m_address);
        mDot = findViewById(R.id.m_dot);
        mStatus = findViewById(R.id.m_status);
        mDetail = findViewById(R.id.m_detail);
        mSchedule = findViewById(R.id.m_schedule);
        mTodayLabel = findViewById(R.id.m_today_label);
        mTodayImg = findViewById(R.id.m_today_img);
        mTodayText = findViewById(R.id.m_today_text);
        mTomorrowLabel = findViewById(R.id.m_tomorrow_label);
        mTomorrowImg = findViewById(R.id.m_tomorrow_img);
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
        bindFlag(R.id.cb_before, Store.F_BEFORE, st);
        bindFlag(R.id.cb_return, Store.F_RETURN, st);
        bindFlag(R.id.cb_emergency, Store.F_EMERGENCY, st);
        bindFlag(R.id.cb_schedule, Store.F_SCHEDULE, st);

        Spinner spLead = findViewById(R.id.sp_lead);
        String[] labels = new String[LEADS.length];
        int sel = 2;
        for (int i = 0; i < LEADS.length; i++) {
            labels[i] = LEADS[i] + " хв";
            if (LEADS[i] == st.lead()) sel = i;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLead.setAdapter(ad);
        spLead.setSelection(sel);
        spLead.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Store s = new Store(MainActivity.this);
                if (s.lead() != LEADS[position]) {
                    s.setLead(LEADS[position]);
                    runTick(false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
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

    private void bindFlag(int id, String key, Store st) {
        CheckBox cb = findViewById(id);
        cb.setChecked(st.flag(key));
        cb.setOnCheckedChangeListener((b, checked) -> {
            new Store(this).setFlag(key, checked);
            runTick(false);
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

    private void runTick(boolean fetch) {
        new Thread(() -> Updater.tick(getApplicationContext(), fetch)).start();
    }

    private void render() {
        Store st = new Store(this);
        Schedule sch = Schedule.load(st);
        ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
        Address a = st.active();
        Status s = Status.compute(sch, a, now);

        mAddress.setText(a == null ? "Адресу ще не додано" : a.name);
        mDot.setTextColor(Status.color(s.level));
        mStatus.setText(s.title);
        mDetail.setText(s.detail);

        if (a != null && sch.hasData) {
            mSchedule.setVisibility(View.VISIBLE);
            LocalDate today = now.toLocalDate();
            LocalDate tomorrow = today.plusDays(1);
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");

            int[] t = a.gpvId > 0 ? sch.slots(today, a.gpvId) : null;
            if (t == null) t = new int[48];
            mTodayLabel.setText("СЬОГОДНІ, " + today.format(f));
            mTodayImg.setImageBitmap(Timeline.render(t, Status.nowFraction(now), false, 1000, 110));
            mTodayText.setText(Status.describeDay(t));

            mTomorrowLabel.setText("ЗАВТРА, " + tomorrow.format(f));
            int[] m = null;
            if (sch.hasDay(tomorrow)) m = a.gpvId > 0 ? sch.slots(tomorrow, a.gpvId) : new int[48];
            if (m != null) {
                mTomorrowImg.setVisibility(View.VISIBLE);
                mTomorrowImg.setImageBitmap(Timeline.render(m, -1f, false, 1000, 110));
                mTomorrowText.setText(Status.describeDay(m));
            } else {
                mTomorrowImg.setVisibility(View.GONE);
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
            empty.setText("Поки що немає адрес. Натисніть «+ Додати адресу».");
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            addrList.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Address a = list.get(i);
            boolean isActive = active != null && active.domId == a.domId;
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(0xFFE3E7ED);
                addrList.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(4), dp(10));

            TextView tv = new TextView(this);
            tv.setText((isActive ? "● " : "○ ") + a.name + "\n" + a.full + "\n" + a.queuesText());
            tv.setTextSize(14);
            tv.setTextColor(getColor(R.color.text_main));
            if (isActive) tv.setTypeface(null, Typeface.BOLD);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            CheckBox bell = new CheckBox(this);
            bell.setText("🔔");
            bell.setChecked(a.notify);
            bell.setOnCheckedChangeListener((b, checked) -> {
                Store s = new Store(this);
                List<Address> all = s.addresses();
                for (Address x : all) if (x.domId == a.domId) x.notify = checked;
                s.saveAddresses(all);
                runTick(false);
            });
            row.addView(bell);

            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextSize(20);
            del.setTextColor(getColor(R.color.text_muted));
            del.setPadding(dp(14), dp(8), dp(14), dp(8));
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Видалити адресу «" + a.name + "»?")
                    .setPositiveButton("Видалити", (d, w) -> {
                        new Store(this).removeAddress(a.domId);
                        render();
                        runTick(false);
                    })
                    .setNegativeButton("Скасувати", null)
                    .show());
            row.addView(del);

            row.setOnClickListener(v -> {
                new Store(this).setActiveDom(a.domId);
                render();
                runTick(false);
            });
            addrList.addView(row);
        }
    }

    private CharSequence legend() {
        SpannableStringBuilder b = new SpannableStringBuilder();
        addLegend(b, Schedule.ENABLE, "є світло");
        addLegend(b, Schedule.PROBABLY, "можливе");
        addLegend(b, Schedule.OFF, "заплановане");
        addLegend(b, Schedule.SURE_OFF, "актуальне відключення");
        return b;
    }

    private void addLegend(SpannableStringBuilder b, int code, String text) {
        int start = b.length();
        b.append("■ ");
        b.setSpan(new ForegroundColorSpan(Status.cellColor(code)), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(text).append("   ");
        b.setSpan(new ForegroundColorSpan(getColor(R.color.text_muted)), start + 2, b.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void updateBatteryButton() {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        btnBattery.setVisibility(ok ? View.GONE : View.VISIBLE);
        mBatteryHint.setText(ok ? "✓ Роботу у фоні дозволено"
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
