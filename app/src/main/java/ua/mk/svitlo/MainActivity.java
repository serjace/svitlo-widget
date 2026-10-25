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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.time.Instant;

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

    /** Opens the app on this address (used by widgets). */
    static final String EXTRA_DOM = "open_dom";

    private View cardUpdate;
    private ViewPager2 pager;
    private ChipGroup chips;
    private HorizontalScrollView chipsScroll;
    private AppBarLayout appBar;
    private int appBarOffset;   // 0 = address buttons fully shown
    private SwipeRefreshLayout swipe;
    private final List<Integer> chipIds = new ArrayList<>();
    private String chipsKey;
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
        chips = findViewById(R.id.main_chips);
        chipsScroll = findViewById(R.id.main_chips_scroll);
        appBar = findViewById(R.id.main_appbar);
        appBar.addOnOffsetChangedListener((bar, offset) -> appBarOffset = offset);

        // Pull to refresh: only when the page is at its very top, never while swiping sideways
        swipe = findViewById(R.id.main_refresh);
        swipe.setColorSchemeColors(MaterialColors.getColor(swipe, androidx.appcompat.R.attr.colorPrimary));
        swipe.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(swipe, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        swipe.setOnChildScrollUpCallback((parent, child) -> appBarOffset != 0 || currentPageScrolled());
        swipe.setOnRefreshListener(() -> refresh(true));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                // Swipe starts: the page sliding in starts at the top.
                // Swipe ends: the page we left goes back to the top too.
                scrollOthersToTop();
                swipe.setEnabled(state == ViewPager2.SCROLL_STATE_IDLE);
            }

            @Override
            public void onPageSelected(int position) {
                checkChip(position);
                appBar.setExpanded(true, true);   // the new page starts at the top, buttons visible
                if (ignorePageEvents || position >= list.size()) return;
                Address x = list.get(position);
                Store st = new Store(MainActivity.this);
                Address cur = st.active();
                if (cur == null || cur.domId != x.domId) {
                    st.setActiveDom(x.domId);
                    PowerWidget.redrawAll(MainActivity.this);
                    runTick();
                }
            }
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) {
                refresh(true);
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
        render();
        PowerWidget.redrawAll(this);
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
        PowerWidget.redrawAll(this);
        Updater.scheduleNext(this, st, sch, now);
        Updater.ensurePeriodic(this);
        // fresh data every time the app is opened (icon, widget, or back from recent apps)
        if (!st.addresses().isEmpty() && System.currentTimeMillis() - st.fetchedAt() > 20 * 1000L) {
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
        PowerWidget.redrawAll(this);
    }

    void refresh() {
        refresh(false);
    }

    /** @param byUser show a small "Оновлено о …" pop-up when done (⟳ was tapped) */
    void refresh(boolean byUser) {
        if (refreshing) return;   // already running; its end also hides the pull circle
        refreshing = true;
        setFooters("Оновлення…");
        new Thread(() -> {
            Updater.tick(getApplicationContext(), true, true);   // queues too
            runOnUiThread(() -> {
                refreshing = false;
                swipe.setRefreshing(false);
                render();
                if (byUser && !isFinishing()) {
                    Store st = new Store(this);
                    String msg = st.lastError() > st.fetchedAt()
                            ? "Не вдалося оновити — немає звʼязку"
                            : "Оновлено о " + Instant.ofEpochMilli(st.fetchedAt()).atZone(Schedule.ZONE)
                                    .format(DateTimeFormatter.ofPattern("HH:mm"));
                    Snackbar.make(pager, msg, Snackbar.LENGTH_SHORT).show();
                }
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
        fillChips();
        adapter.notifyDataSetChanged();
        if (pager.getCurrentItem() != idx) pager.setCurrentItem(idx, false);
        checkChip(pager.getCurrentItem());
        pager.post(() -> ignorePageEvents = false);

        renderUpdate();
    }

    /** The "Оновлено …" line of the pages on screen, without redrawing them. */
    private void setFooters(String text) {
        if (!(pager.getChildAt(0) instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) pager.getChildAt(0);
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
            if (h instanceof Page) ((Page) h).mUpdated.setText(text);
        }
    }

    /** Is the page on screen scrolled down (so a pull should scroll it, not refresh)? */
    private boolean currentPageScrolled() {
        if (!(pager.getChildAt(0) instanceof RecyclerView)) return false;
        RecyclerView.ViewHolder h = ((RecyclerView) pager.getChildAt(0))
                .findViewHolderForAdapterPosition(pager.getCurrentItem());
        return h instanceof Page && ((Page) h).scroll.canScrollVertically(-1);
    }

    /** Pages that are not the one on screen go back to the top. */
    private void scrollOthersToTop() {
        if (!(pager.getChildAt(0) instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) pager.getChildAt(0);
        int cur = pager.getCurrentItem();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            RecyclerView.ViewHolder h = rv.getChildViewHolder(child);
            if (h instanceof Page && h.getAdapterPosition() != cur) ((Page) h).scroll.scrollTo(0, 0);
        }
    }

    private void checkChip(int pos) {
        if (pos < 0 || pos >= chipIds.size()) return;
        chips.check(chipIds.get(pos));
        // keep the selected button in view (centred) when the row is wider than the screen
        View chip = chips.findViewById(chipIds.get(pos));
        chipsScroll.post(() -> {
            if (chip == null) return;
            int x = chipsScroll.getPaddingLeft() + chip.getLeft() + chip.getWidth() / 2 - chipsScroll.getWidth() / 2;
            chipsScroll.smoothScrollTo(Math.max(0, x), 0);
        });
    }

    /**
     * Big centred "Дім / Робота" buttons above the pages. They stay in place; swiping only moves
     * the selection. Long press = edit.
     */
    private void fillChips() {
        StringBuilder key = new StringBuilder();
        for (Address x : list) key.append(x.domId).append(':').append(x.name).append('\n');
        if (key.toString().equals(chipsKey)) return;   // nothing changed: keep the buttons as they are
        chipsKey = key.toString();
        chips.removeAllViews();
        chipIds.clear();
        if (list.size() < 2) {
            chipsScroll.setVisibility(View.GONE);
            return;
        }
        chipsScroll.setVisibility(View.VISIBLE);
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < list.size(); i++) {
            final int pos = i;
            Address x = list.get(i);
            Chip chip = (Chip) inf.inflate(R.layout.chip_address, chips, false);
            chip.setId(View.generateViewId());
            chip.setText(x.name);
            chips.addView(chip);
            chipIds.add(chip.getId());
            chip.setOnClickListener(v -> {
                if (pos != pager.getCurrentItem()) pager.setCurrentItem(pos, true);
                else checkChip(pos);
            });
            chip.setOnLongClickListener(v -> {
                startActivity(new Intent(this, AddAddressActivity.class)
                        .putExtra(AddAddressActivity.EXTRA_REPLACE, x.domId));
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
        final TextView addrName, addrFull, mTodayLabel, mTomorrowLabel,
                mTodayText, mTomorrowText, mUpdated, mQueues;
        final LinearLayout mNext;
        final HourGridView mTodayGrid, mTomorrowGrid;
        final View content;
        final NestedScrollView scroll;
        int boundPos = -1;

        Page(View v) {
            super(v);
            content = v.findViewById(R.id.page_content);
            scroll = (NestedScrollView) v;
            addrBox = v.findViewById(R.id.p_addr_box);
            addrName = v.findViewById(R.id.p_addr_name);
            addrFull = v.findViewById(R.id.p_addr_full);
            btnAddFirst = v.findViewById(R.id.btn_add_first);
            cardNext = v.findViewById(R.id.card_next);
            mNext = v.findViewById(R.id.m_next);
            mTodayText = v.findViewById(R.id.m_today_text);
            cardToday = v.findViewById(R.id.card_today);
            mTodayLabel = v.findViewById(R.id.m_today_label);
            mTodayGrid = v.findViewById(R.id.m_today_grid);
            cardTomorrow = v.findViewById(R.id.card_tomorrow);
            mTomorrowLabel = v.findViewById(R.id.m_tomorrow_label);
            mTomorrowText = v.findViewById(R.id.m_tomorrow_text);
            mTomorrowGrid = v.findViewById(R.id.m_tomorrow_grid);
            mTomorrowMore = v.findViewById(R.id.m_tomorrow_more);
            mUpdated = v.findViewById(R.id.m_updated);
            mQueues = v.findViewById(R.id.m_queues);
            ((TextView) v.findViewById(R.id.m_legend)).setText(legend());
        }

        void bind(Address a, int pos) {
            Bars.padBottom(content, navInset);
            // a reused page showing another address starts at the top
            if (boundPos != pos) scroll.scrollTo(0, 0);
            boundPos = pos;
            if (sch == null) return;
            Status s = Status.computeForApp(sch, a, now);

            // Which address
            if (a != null) {
                // with several addresses the selected button already shows the name
                addrBox.setVisibility(list.size() > 1 ? View.GONE : View.VISIBLE);
                addrName.setText(a.name);
                String q = a.gpvId > 0 ? " · черга " + a.gpvName : "";
                addrFull.setText(a.full + q);
                addrFull.setVisibility(View.VISIBLE);
            } else {
                addrBox.setVisibility(View.GONE);
                addrFull.setVisibility(View.GONE);
            }

            if (a == null) {
                btnAddFirst.setText("Додати адресу");
                btnAddFirst.setOnClickListener(x ->
                        startActivity(new Intent(MainActivity.this, AddAddressActivity.class)));
                btnAddFirst.setVisibility(View.VISIBLE);
            } else if (!a.hasQueues()) {
                btnAddFirst.setText("Редагувати адресу");
                btnAddFirst.setOnClickListener(x -> startActivity(new Intent(MainActivity.this, AddAddressActivity.class)
                        .putExtra(AddAddressActivity.EXTRA_REPLACE, a.domId)));
                btnAddFirst.setVisibility(View.VISIBLE);
            } else {
                btnAddFirst.setVisibility(View.GONE);
            }

            boolean show = a != null && sch.hasData;
            cardNext.setVisibility(a == null ? View.GONE : View.VISIBLE);
            mNext.removeAllViews();
            if (a != null) {
                LayoutInflater inf = getLayoutInflater();
                for (int[] period : s.remaining()) {
                    boolean current = period[0] == s.nowIdx;
                    View row = inf.inflate(R.layout.item_period, mNext, false);
                    row.findViewById(R.id.p_pill).setBackgroundResource(Timeline.pillRes(period[2]));
                    TextView time = row.findViewById(R.id.p_time);
                    TextView label = row.findViewById(R.id.p_label);
                    time.setText(Status.rowTime(period, s.nowIdx).replace("–", " – "));
                    label.setText(current ? s.level == Status.UNKNOWN ? "Немає даних" : s.title
                            : Status.rowLabel(period));
                    if (current) {
                        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
                        row.setBackgroundResource(R.drawable.row_now_bg);
                        row.setPadding(pad, pad, pad, pad);
                        time.setTextSize(19);
                        label.setTextSize(23);
                        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
                        label.setTextColor(MaterialColors.getColor(label, com.google.android.material.R.attr.colorOnSurface));
                    }
                    mNext.addView(row);
                }
            }
            cardToday.setVisibility(show ? View.VISIBLE : View.GONE);
            cardTomorrow.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                LocalDate today = now.toLocalDate();
                LocalDate tomorrow = today.plusDays(1);
                DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");

                List<Works.Work> works = Works.events(sch.works(a.domId));
                mTodayText.setText(Status.cardSummary(sch, a, today, now));
                mTomorrowText.setText(Status.cardSummary(sch, a, tomorrow, now));

                // the hour grids show these works too: emergency = no light, planned = possible
                int[] t = a.gpvId > 0 ? sch.slots(today, a.gpvId) : null;
                if (t == null) {
                    t = new int[48];
                    java.util.Arrays.fill(t, -1);
                }
                t = Works.paint(t, works, today, now);
                mTodayLabel.setText("Сьогодні, " + today.format(f));
                mTodayGrid.setData(t, now.getHour());

                mTomorrowLabel.setText("Завтра, " + tomorrow.format(f));
                int[] m = null;
                if (a.gpvId > 0) m = sch.slots(tomorrow, a.gpvId);
                if (m != null) m = Works.paint(m, works, tomorrow, now);
                else m = Works.knownDay(works, tomorrow, now);
                if (m != null) {
                    mTomorrowGrid.setVisibility(View.VISIBLE);
                    mTomorrowGrid.setData(m, -1);
                } else {
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
            mUpdated.setVisibility(a == null ? View.GONE : View.VISIBLE);
            // All queues of this address (re-read from the website every hour and on ⟳)
            mQueues.setText(a == null ? "" : "Черги: " + a.queuesText());
            mQueues.setVisibility(a == null ? View.GONE : View.VISIBLE);
        }
    }
}
