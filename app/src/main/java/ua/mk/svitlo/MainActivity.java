package ua.mk.svitlo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The main screen: only what matters, in big letters. Settings live in SettingsActivity. */
public class MainActivity extends AppCompatActivity {
    private static final int MAX_ROWS = 4;

    private View addrScroll, cardNext, cardToday, cardTomorrow, cardUpdate, btnAddFirst;
    private ChipGroup cgAddresses;
    private MaterialCardView cardHero;
    private ImageView hIcon;
    private TextView hTitle, hLine1, hLine2, mTodayLabel, mTomorrowLabel, mTomorrowText, mQueues, mUpdated;
    private LinearLayout mNext;
    private HourGridView mTodayGrid, mTomorrowGrid;
    private MaterialButton btnRefresh;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        addrScroll = findViewById(R.id.addr_scroll);
        cgAddresses = findViewById(R.id.cg_addresses);
        cardUpdate = findViewById(R.id.card_update);
        cardHero = findViewById(R.id.card_hero);
        hIcon = findViewById(R.id.h_icon);
        hTitle = findViewById(R.id.h_title);
        hLine1 = findViewById(R.id.h_line1);
        hLine2 = findViewById(R.id.h_line2);
        btnAddFirst = findViewById(R.id.btn_add_first);
        cardNext = findViewById(R.id.card_next);
        mNext = findViewById(R.id.m_next);
        cardToday = findViewById(R.id.card_today);
        mTodayLabel = findViewById(R.id.m_today_label);
        mTodayGrid = findViewById(R.id.m_today_grid);
        cardTomorrow = findViewById(R.id.card_tomorrow);
        mTomorrowLabel = findViewById(R.id.m_tomorrow_label);
        mTomorrowText = findViewById(R.id.m_tomorrow_text);
        mTomorrowGrid = findViewById(R.id.m_tomorrow_grid);
        mQueues = findViewById(R.id.m_queues);
        mUpdated = findViewById(R.id.m_updated);
        btnRefresh = findViewById(R.id.btn_refresh);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share) {
                SettingsActivity.share(this);
                return true;
            }
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        ((TextView) findViewById(R.id.m_legend)).setText(legend());
        btnRefresh.setOnClickListener(v -> refresh());
        btnAddFirst.setOnClickListener(v -> startActivity(new Intent(this, AddAddressActivity.class)));
        findViewById(R.id.btn_update).setOnClickListener(v -> {
            AppUpdate.download(getApplicationContext());
            cardUpdate.setVisibility(View.GONE);
            Toast.makeText(this, "Завантаження… Коли завершиться, відкриється встановлення.",
                    Toast.LENGTH_LONG).show();
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
        Store st = new Store(this);
        if (!st.addresses().isEmpty() && System.currentTimeMillis() - st.fetchedAt() > 5 * 60 * 1000L) {
            refresh();
        }
        if (System.currentTimeMillis() - st.updateCheckedAt() > 30 * 60 * 1000L) {
            new Thread(() -> {
                AppUpdate.check(getApplicationContext(), new Store(getApplicationContext()));
                runOnUiThread(this::renderUpdate);
            }).start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Updater.setListener(null);
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
        List<Address> list = st.addresses();
        Address a = st.active();
        Status s = Status.compute(sch, a, now);

        renderSwitcher(list, a);

        // Big status card in the status colour
        int fill = getColor(Timeline.containerColor(s.level));
        int on = getColor(Timeline.contentColor(s.level));
        cardHero.setCardBackgroundColor(fill);
        hIcon.setImageResource(Timeline.iconRes(s.level));
        hTitle.setText(s.title);
        hTitle.setTextColor(on);
        hLine1.setText(s.line1);
        hLine1.setTextColor(on);
        hLine1.setVisibility(s.line1.isEmpty() ? View.GONE : View.VISIBLE);
        hLine2.setText(s.line2);
        hLine2.setTextColor(on);
        hLine2.setVisibility(s.line2.isEmpty() ? View.GONE : View.VISIBLE);
        btnAddFirst.setVisibility(a == null ? View.VISIBLE : View.GONE);

        boolean show = a != null && sch.hasData;
        if (show) {
            LocalDate today = now.toLocalDate();
            LocalDate tomorrow = today.plusDays(1);
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");

            // Coming hours
            mNext.removeAllViews();
            LayoutInflater inf = getLayoutInflater();
            List<int[]> up = s.upcoming(MAX_ROWS);
            for (int[] r : up) {
                View row = inf.inflate(R.layout.item_period, mNext, false);
                row.findViewById(R.id.p_pill).setBackgroundResource(Timeline.pillRes(r[2]));
                ((TextView) row.findViewById(R.id.p_time)).setText(Status.rowTime(r, s.nowIdx).replace("–", " – "));
                ((TextView) row.findViewById(R.id.p_label)).setText(Status.rowLabel(r));
                mNext.addView(row);
            }
            cardNext.setVisibility(up.isEmpty() ? View.GONE : View.VISIBLE);

            // Today
            int[] t = a.gpvId > 0 ? sch.slots(today, a.gpvId) : null;
            if (t == null) t = new int[48];
            mTodayLabel.setText("Сьогодні, " + today.format(f));
            mTodayGrid.setData(t, now.getHour());
            cardToday.setVisibility(View.VISIBLE);

            // Tomorrow
            mTomorrowLabel.setText("Завтра, " + tomorrow.format(f));
            int[] m = null;
            if (sch.hasDay(tomorrow)) m = a.gpvId > 0 ? sch.slots(tomorrow, a.gpvId) : new int[48];
            if (m != null) {
                mTomorrowText.setText(Status.daySummary(m));
                mTomorrowGrid.setVisibility(View.VISIBLE);
                mTomorrowGrid.setData(m, -1);
            } else {
                mTomorrowText.setText("Графік ще не опубліковано");
                mTomorrowGrid.setVisibility(View.GONE);
            }
            cardTomorrow.setVisibility(View.VISIBLE);

            mQueues.setText(a.full + "\n" + a.queuesText());
            mQueues.setVisibility(View.VISIBLE);
        } else {
            cardNext.setVisibility(View.GONE);
            cardToday.setVisibility(View.GONE);
            cardTomorrow.setVisibility(View.GONE);
            mQueues.setVisibility(View.GONE);
        }
        if (!refreshing) mUpdated.setText(st.updatedLabel());
        renderUpdate();
    }

    /** Big "Дім / Батьки" buttons on top when there are several addresses. */
    private void renderSwitcher(List<Address> list, Address active) {
        cgAddresses.removeAllViews();
        if (list.size() < 2) {
            addrScroll.setVisibility(View.GONE);
            return;
        }
        addrScroll.setVisibility(View.VISIBLE);
        LayoutInflater inf = getLayoutInflater();
        for (Address x : list) {
            Chip chip = (Chip) inf.inflate(R.layout.chip_address, cgAddresses, false);
            chip.setId(View.generateViewId());
            chip.setText(x.name);
            cgAddresses.addView(chip);
            if (active != null && active.domId == x.domId) cgAddresses.check(chip.getId());
            chip.setOnClickListener(v -> {
                new Store(this).setActiveDom(x.domId);
                render();
                runTick();
            });
        }
    }

    private void renderUpdate() {
        Store st = new Store(this);
        boolean show = AppUpdate.available(this, st);
        cardUpdate.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            ((TextView) findViewById(R.id.tv_update)).setText("Доступна нова версія 1.0." + st.updateCode());
        }
    }

    private CharSequence legend() {
        SpannableStringBuilder b = new SpannableStringBuilder();
        addLegend(b, getColor(Timeline.strongColor(Timeline.ON)), "світло є");
        addLegend(b, getColor(Timeline.strongColor(Timeline.MAYBE)), "можливе");
        b.append("\n");
        addLegend(b, getColor(Timeline.strongColor(Timeline.OFF)), "світла немає");
        addLegend(b, getColor(R.color.grid_past_fill), "минуло");
        return b;
    }

    private void addLegend(SpannableStringBuilder b, int color, String text) {
        int start = b.length();
        b.append("■ ");
        b.setSpan(new ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(text).append("    ");
    }
}
