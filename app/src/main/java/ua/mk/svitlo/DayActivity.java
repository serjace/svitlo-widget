package ua.mk.svitlo;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** A whole day for one address: what was, what is now and what will be. */
public class DayActivity extends AppCompatActivity {
    static final String EXTRA_DOM = "dom";
    static final String EXTRA_DAY = "day";   // 0 = today, 1 = tomorrow

    private long domId;
    private int day;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day);
        domId = getIntent().getLongExtra(EXTRA_DOM, 0);
        day = getIntent().getIntExtra(EXTRA_DAY, 0);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        Store st = new Store(this);
        Address a = st.find(domId);
        if (a == null) {
            finish();
            return;
        }
        Schedule sch = Schedule.load(st);
        ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
        LocalDate date = now.toLocalDate().plusDays(day);
        int ni = Status.nowIndex(now);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle((day == 0 ? "Сьогодні, " : "Завтра, ")
                + date.format(DateTimeFormatter.ofPattern("dd.MM")));
        ((TextView) findViewById(R.id.d_addr)).setText(a.name);
        ((TextView) findViewById(R.id.d_full)).setText(a.full + (a.gpvId > 0 ? " · черга " + a.gpvName : ""));

        int[] slots = a.gpvId > 0 ? sch.slots(date, a.gpvId) : null;
        if (slots == null && day == 0 && a.gpvId > 0 && sch.hasData) slots = new int[48];
        TextView summary = findViewById(R.id.d_summary);
        HourGridView grid = findViewById(R.id.d_grid);
        LinearLayout list = findViewById(R.id.d_list);
        list.removeAllViews();
        if (slots == null) {
            summary.setText(a.gpvId > 0 ? "Графік ще не опубліковано" : "Черга невідома");
            grid.setVisibility(View.GONE);
            return;
        }
        summary.setText(Status.daySummary(slots));
        grid.setVisibility(View.VISIBLE);
        grid.setData(slots, day == 0 ? now.getHour() : -1);

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
            String label = Timeline.name(lvl);
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
