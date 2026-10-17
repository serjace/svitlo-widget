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
        TabLayout tabs = findViewById(R.id.day_tabs);
        new TabLayoutMediator(tabs, pager, (tab, pos) -> tab.setText(dayLabel(pos))).attach();
        pager.setCurrentItem(day, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        adapter.notifyDataSetChanged();
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
            int ni = Status.nowIndex(now);
            full.setText(a.full + (a.gpvId > 0 ? " · черга " + a.gpvName : ""));

            int[] slots = a.gpvId > 0 ? sch.slots(date, a.gpvId) : null;
            if (slots == null && day == 0 && a.gpvId > 0 && sch.hasData) slots = new int[48];
            list.removeAllViews();
            if (slots == null) {
                summary.setText(a.gpvId == 0 ? "Черга невідома"
                        : day == 1 ? "Графік на завтра ще не опубліковано.\nЗазвичай він зʼявляється ввечері."
                        : "Немає даних");
                grid.setVisibility(View.GONE);
                card.setVisibility(View.GONE);
                return;
            }
            summary.setText(Status.daySummary(slots));
            grid.setVisibility(View.VISIBLE);
            grid.setData(slots, day == 0 ? now.getHour() : -1);
            card.setVisibility(View.VISIBLE);

            LayoutInflater inf = getLayoutInflater();
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            int i = 0;
            while (i < 48) {
                int lvl = Timeline.level(slots[i]);
                int e = i;
                while (e < 48 && Timeline.level(slots[e]) == lvl) e++;
                boolean past = day == 0 && e <= ni;
                boolean current = day == 0 && i <= ni && ni < e;

                View row = inf.inflate(R.layout.item_period, list, false);
                row.findViewById(R.id.p_pill).setBackgroundResource(Timeline.pillRes(lvl));
                ((TextView) row.findViewById(R.id.p_time)).setText(Status.hm(i) + " – " + Status.hm(e));
                String label = lvl == Timeline.OFF ? "Світло відсутнє" : Timeline.name(lvl);
                if (past) label += " · минуло";
                if (current) label += " · зараз";
                ((TextView) row.findViewById(R.id.p_label)).setText(label);
                if (past) row.setAlpha(0.45f);
                if (current) {
                    row.setBackgroundResource(R.drawable.row_now_bg);
                    row.setPadding(pad, pad, pad, pad);
                }
                list.addView(row);
                i = e;
            }
        }
    }
}
