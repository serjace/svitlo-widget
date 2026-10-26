package ua.mk.svitlo;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A whole day for one address: what was, what is now and what will be.
 * Two tabs, "Сьогодні" and "Завтра"; swipe or tap to switch.
 */
public class DayActivity extends AppCompatActivity {
    static final String EXTRA_DOM = "dom";
    static final String EXTRA_DAY = "day";   // 0 = today, 1 = tomorrow

    private long domId;
    private int bottomInset;
    private final DaysAdapter adapter = new DaysAdapter();

    // refreshed in onResume
    private Address address;
    private Schedule sch;
    private ZonedDateTime now;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day);
        Bars.apply(this, findViewById(R.id.day_root), bottom -> {
            bottomInset = bottom;
            adapter.notifyDataSetChanged();
        });
        domId = getIntent().getLongExtra(EXTRA_DOM, 0);
        int day = getIntent().getIntExtra(EXTRA_DAY, 0);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        load();
        ViewPager2 pager = findViewById(R.id.day_pager);
        pager.setAdapter(adapter);
        Bars.smoothPager(pager);
        TabLayout tabs = findViewById(R.id.day_tabs);
        if (getResources().getConfiguration().fontScale > 1.05f) tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        new TabLayoutMediator(tabs, pager, (tab, pos) -> tab.setText(dayLabel(pos))).attach();
        pager.setCurrentItem(day, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.setListener(this::render);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Updater.setListener(null);
    }

    private void render() {
        load();
        adapter.notifyDataSetChanged();
        ViewPager2 pager = findViewById(R.id.day_pager);
        pager.post(pager::requestTransform);
        TabLayout tabs = findViewById(R.id.day_tabs);
        for (int i = 0; i < tabs.getTabCount(); i++) {
            TabLayout.Tab tab = tabs.getTabAt(i);
            if (tab != null) tab.setText(dayLabel(i));
        }
    }

    private void load() {
        Store st = new Store(this);
        address = st.find(domId);
        if (address == null) {
            finish();
            return;
        }
        sch = Schedule.load(st);
        now = ZonedDateTime.now(Schedule.ZONE);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle(address.name);
    }

    private String dayLabel(int pos) {
        LocalDate d = ZonedDateTime.now(Schedule.ZONE).toLocalDate().plusDays(pos);
        return (pos == 0 ? "Сьогодні, " : "Завтра, ") + d.format(DateTimeFormatter.ofPattern("dd.MM"));
    }

    private class DaysAdapter extends RecyclerView.Adapter<DayPage> {
        @NonNull
        @Override
        public DayPage onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DayPage(getLayoutInflater().inflate(R.layout.page_day, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull DayPage holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }

    private class DayPage extends RecyclerView.ViewHolder {
        final View content, card;
        final TextView full, summary;
        final HourGridView grid;
        final LinearLayout list;

        DayPage(View v) {
            super(v);
            content = v.findViewById(R.id.day_content);
            card = v.findViewById(R.id.d_card);
            full = v.findViewById(R.id.d_full);
            summary = v.findViewById(R.id.d_summary);
            grid = v.findViewById(R.id.d_grid);
            list = v.findViewById(R.id.d_list);
        }

        void bind(int day) {
            Bars.padBottom(content, bottomInset);
            if (address == null || sch == null) return;
            Address a = address;
            LocalDate date = now.toLocalDate().plusDays(day);
            full.setText(a.full + (a.gpvId > 0 ? " · черга " + a.gpvName : ""));

            java.util.List<DayOutages.Entry> entries = DayOutages.entries(sch, a, date, now);
            int[] slots = a.gpvId > 0 ? sch.slots(date, a.gpvId) : null;
            if (slots == null) slots = Works.knownDay(sch.works(a.domId), date, now);
            else slots = Works.paint(slots, sch.works(a.domId), date, now);
            summary.setText(entries.isEmpty() ? DayOutages.emptyText(sch, a, date) : "");
            summary.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            grid.setVisibility(slots == null ? View.GONE : View.VISIBLE);
            if (slots != null) grid.setData(slots, day == 0 ? now.getHour() : -1);
            card.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
            list.removeAllViews();
            LayoutInflater inf = getLayoutInflater();
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            for (DayOutages.Entry entry : entries) {
                View row = inf.inflate(R.layout.item_period, list, false);
                row.findViewById(R.id.p_pill).setBackgroundResource(Timeline.pillRes(entry.level));
                ((TextView) row.findViewById(R.id.p_time)).setText(entry.time);
                String label = entry.label;
                if (entry.past) label += " · минуло";
                if (entry.current) label += " · зараз";
                ((TextView) row.findViewById(R.id.p_label)).setText(label);
                if (entry.past) row.setAlpha(0.45f);
                if (entry.current) {
                    row.setBackgroundResource(R.drawable.row_now_bg);
                    row.setPadding(pad, pad, pad, pad);
                }
                list.addView(row);
            }
        }
    }
}
