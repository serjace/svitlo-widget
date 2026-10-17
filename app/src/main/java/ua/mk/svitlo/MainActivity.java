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
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The main screen: one page per address, swipe left/right between them.
 * Only what matters, in big letters. Settings live in SettingsActivity.
 */
public class MainActivity extends AppCompatActivity {
    private static final int MAX_ROWS = 4;

    /** Opens the app on this address (used by widgets). */
    static final String EXTRA_DOM = "open_dom";

    private View cardUpdate;
    private ViewPager2 pager;
    private int navInset;   // space to keep free at the bottom for the (transparent) gesture bar
    private final PagesAdapter adapter = new PagesAdapter();
    private boolean refreshing;
    private boolean ignorePageEvents;

    // data shared by all pages, refreshed in render()
    private List<Address> list = new ArrayList<>();
    private Schedule sch;
    private ZonedDateTime now;
    private String updated = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Bars.apply(this, findViewById(R.id.main_root), bottom -> {
            navInset = bottom;
            adapter.notifyDataSetChanged();
        });
        openFromIntent(getIntent());
        cardUpdate = findViewById(R.id.card_update);
        pager = findViewById(R.id.pager);
        pager.setAdapter(adapter);
        Bars.smoothPager(pager);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (ignorePageEvents || position >= list.size()) return;
                Address x = list.get(position);
                Store st = new Store(MainActivity.this);
                Address cur = st.active();
                if (cur == null || cur.domId != x.domId) {
                    st.setActiveDom(x.domId);
                    runTick();
                }
            }
        });

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

        findViewById(R.id.btn_update).setOnClickListener(v -> {
            AppUpdate.download(getApplicationContext());
            cardUpdate.setVisibility(View.GONE);
            Toast.makeText(this, "Завантаження… Коли завершиться, відкриється встановлення.",
                    Toast.LENGTH_LONG).show();
        });

        Store st0 = new Store(this);
        if (SettingsActivity.isXiaomi() && !st0.seen("miui_tip")) {
            st0.markSeen("miui_tip");
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Телефон Xiaomi: потрібні 2 дозволи")
                    .setMessage("Щоб віджет відкривав застосунок, а сповіщення приходили вчасно, "
                            + "MIUI потребує двох дозволів для «Світло»:\n\n"
                            + "1. «Автозапуск» — увімкнути.\n\n"
                            + "2. «Інші дозволи» → «Відображати спливаючі вікна у фоновому режимі» — дозволити.\n\n"
                            + "Ці кнопки також є в Налаштуваннях (⚙).")
                    .setPositiveButton("1. Автозапуск", (d, w) -> SettingsActivity.openAutostart(this))
                    .setNeutralButton("2. Інші дозволи", (d, w) -> SettingsActivity.openMiuiPermissions(this))
                    .setNegativeButton("Пізніше", null)
                    .show();
        }

        Notifier.createChannels(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** A widget was tapped: show that widget's address. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    private void openFromIntent(Intent intent) {
        long dom = intent == null ? 0 : intent.getLongExtra(EXTRA_DOM, 0);
        if (dom <= 0) return;
        intent.removeExtra(EXTRA_DOM);
        Store st = new Store(this);
        if (st.find(dom) != null) st.setActiveDom(dom);
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
        // look for a new app version every time the app is opened
        if (System.currentTimeMillis() - st.updateCheckedAt() > 2 * 60 * 1000L) {
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

    void refresh() {
        if (refreshing) return;
        refreshing = true;
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            Updater.tick(getApplicationContext(), true);
            runOnUiThread(() -> {
                refreshing = false;
                render();
            });
        }).start();
    }

    private void runTick() {
        new Thread(() -> Updater.tick(getApplicationContext(), false)).start();
    }

    private void render() {
        Store st = new Store(this);
        sch = Schedule.load(st);
        now = ZonedDateTime.now(Schedule.ZONE);
        list = st.addresses();
        updated = st.updatedLabel();
        Address active = st.active();
        int idx = 0;
        for (int i = 0; i < list.size(); i++) if (active != null && list.get(i).domId == active.domId) idx = i;

        ignorePageEvents = true;
        adapter.notifyDataSetChanged();
        if (pager.getCurrentItem() != idx) pager.setCurrentItem(idx, false);
        pager.post(() -> ignorePageEvents = false);

        renderUpdate();
    }

    /** Big centred "Дім / Робота" buttons at the top of each page. Long press = rename. */
    private void fillChips(ChipGroup group, int pagePos) {
        group.removeAllViews();
        if (list.size() < 2) {
            group.setVisibility(View.GONE);
            return;
        }
        group.setVisibility(View.VISIBLE);
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < list.size(); i++) {
            final int pos = i;
            Address x = list.get(i);
            Chip chip = (Chip) inf.inflate(R.layout.chip_address, group, false);
            chip.setId(View.generateViewId());
            chip.setText(x.name);
            group.addView(chip);
            if (i == pagePos) group.check(chip.getId());
            chip.setOnClickListener(v -> {
                if (pos != pagePos) pager.setCurrentItem(pos, true);
                else group.check(v.getId());
            });
            chip.setOnLongClickListener(v -> {
                SettingsActivity.rename(this, x.domId, this::render);
                return true;
            });
        }
    }

    private void openDay(Address a, int day) {
        startActivity(new Intent(this, DayActivity.class)
                .putExtra(DayActivity.EXTRA_DOM, a.domId)
                .putExtra(DayActivity.EXTRA_DAY, day));
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

    // ---- pages ----

    private class PagesAdapter extends RecyclerView.Adapter<Page> {
        @NonNull
        @Override
        public Page onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Page(getLayoutInflater().inflate(R.layout.page_address, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Page holder, int position) {
            holder.bind(position < list.size() ? list.get(position) : null, position);
        }

        @Override
        public int getItemCount() {
            return Math.max(1, list.size());   // one "add an address" page when there are none
        }
    }

    private class Page extends RecyclerView.ViewHolder {
        final View addrBox, cardNext, cardToday, cardTomorrow, mTomorrowMore;
        final MaterialButton btnAddFirst;
        final TextView addrName, addrFull, hTitle, hLine1, hLine2, mTodayLabel, mTomorrowLabel,
                mTomorrowText, mUpdated;
        final MaterialCardView cardHero;
        final ImageView hIcon;
        final LinearLayout mNext;
        final HourGridView mTodayGrid, mTomorrowGrid;
        final MaterialButton btnRefresh;
        final ChipGroup chips;
        final View content;

        Page(View v) {
            super(v);
            chips = v.findViewById(R.id.page_chips);
            content = v.findViewById(R.id.page_content);
            addrBox = v.findViewById(R.id.p_addr_box);
            addrName = v.findViewById(R.id.p_addr_name);
            addrFull = v.findViewById(R.id.p_addr_full);
            cardHero = v.findViewById(R.id.card_hero);
            hIcon = v.findViewById(R.id.h_icon);
            hTitle = v.findViewById(R.id.h_title);
            hLine1 = v.findViewById(R.id.h_line1);
            hLine2 = v.findViewById(R.id.h_line2);
            btnAddFirst = v.findViewById(R.id.btn_add_first);
            cardNext = v.findViewById(R.id.card_next);
            mNext = v.findViewById(R.id.m_next);
            cardToday = v.findViewById(R.id.card_today);
            mTodayLabel = v.findViewById(R.id.m_today_label);
            mTodayGrid = v.findViewById(R.id.m_today_grid);
            cardTomorrow = v.findViewById(R.id.card_tomorrow);
            mTomorrowLabel = v.findViewById(R.id.m_tomorrow_label);
            mTomorrowText = v.findViewById(R.id.m_tomorrow_text);
            mTomorrowGrid = v.findViewById(R.id.m_tomorrow_grid);
            mTomorrowMore = v.findViewById(R.id.m_tomorrow_more);
            mUpdated = v.findViewById(R.id.m_updated);
            btnRefresh = v.findViewById(R.id.btn_refresh);
            ((TextView) v.findViewById(R.id.m_legend)).setText(legend());
            btnRefresh.setOnClickListener(x -> refresh());
        }

        void bind(Address a, int pos) {
            Bars.padBottom(content, navInset);
            if (sch == null) return;
            fillChips(chips, pos);
            Status s = Status.compute(sch, a, now);

            // Which address
            if (a != null) {
                addrBox.setVisibility(View.VISIBLE);
                addrName.setText(a.name);
                String q = a.gpvId > 0 ? " · черга " + a.gpvName : "";
                addrFull.setText(a.full + q);
                addrFull.setVisibility(View.VISIBLE);
            } else {
                addrBox.setVisibility(View.GONE);
                addrFull.setVisibility(View.GONE);
            }

            // Big status card in the status colour
            int on = getColor(Timeline.contentColor(s.level));
            cardHero.setCardBackgroundColor(getColor(Timeline.containerColor(s.level)));
            hIcon.setImageResource(Timeline.iconRes(s.level));
            hTitle.setText(s.title);
            hTitle.setTextColor(on);
            hLine1.setText(s.line1);
            hLine1.setTextColor(on);
            hLine1.setVisibility(s.line1.isEmpty() ? View.GONE : View.VISIBLE);
            hLine2.setText(s.line2);
            hLine2.setTextColor(on);
            hLine2.setVisibility(s.line2.isEmpty() ? View.GONE : View.VISIBLE);
            if (a == null) {
                btnAddFirst.setText("Додати адресу");
                btnAddFirst.setOnClickListener(x ->
                        startActivity(new Intent(MainActivity.this, AddAddressActivity.class)));
                btnAddFirst.setVisibility(View.VISIBLE);
            } else if (!a.hasQueues()) {
                btnAddFirst.setText("Змінити адресу");
                btnAddFirst.setOnClickListener(x -> startActivity(new Intent(MainActivity.this, AddAddressActivity.class)
                        .putExtra(AddAddressActivity.EXTRA_REPLACE, a.domId)));
                btnAddFirst.setVisibility(View.VISIBLE);
            } else {
                btnAddFirst.setVisibility(View.GONE);
            }

            boolean show = a != null && sch.hasData;
            cardNext.setVisibility(View.GONE);
            cardToday.setVisibility(show ? View.VISIBLE : View.GONE);
            cardTomorrow.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                LocalDate today = now.toLocalDate();
                LocalDate tomorrow = today.plusDays(1);
                DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");

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

                int[] t = a.gpvId > 0 ? sch.slots(today, a.gpvId) : null;
                if (t == null) t = new int[48];
                mTodayLabel.setText("Сьогодні, " + today.format(f));
                mTodayGrid.setData(t, now.getHour());

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
                mTomorrowMore.setVisibility(m != null ? View.VISIBLE : View.GONE);

                // Tap a day to see the whole day
                cardToday.setOnClickListener(x -> openDay(a, 0));
                if (m != null) cardTomorrow.setOnClickListener(x -> openDay(a, 1));
                else cardTomorrow.setOnClickListener(null);
                cardTomorrow.setClickable(m != null);
            }
            mUpdated.setText(refreshing ? "Оновлення…" : updated);
            btnRefresh.setEnabled(!refreshing);
            btnRefresh.setVisibility(a == null ? View.GONE : View.VISIBLE);
        }
    }
}
