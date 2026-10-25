package ua.mk.svitlo;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/** Address-specific details inside the day they affect, including overnight works. */
final class WorkRows {
    static void fill(LinearLayout parent, List<Works.Work> works, LocalDate day, ZonedDateTime now) {
        parent.removeAllViews();
        long start = day.atStartOfDay(Schedule.ZONE).toEpochSecond();
        long stop = day.plusDays(1).atStartOfDay(Schedule.ZONE).toEpochSecond();
        for (Works.Work w : works) {
            long end = w.overdue(now.toEpochSecond()) ? Math.max(w.end, now.toEpochSecond() + 1) : w.end;
            if (w.start >= stop || end <= start) continue;
            View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_period, parent, false);
            row.findViewById(R.id.p_pill).setBackgroundResource(
                    Timeline.pillRes(w.emergency() ? Timeline.OFF : Timeline.MAYBE));
            String time = (w.start < start ? "00:00" : Works.hm(w.start)) + " – "
                    + (end >= stop ? "24:00" : Works.hm(end));
            ((TextView) row.findViewById(R.id.p_time)).setText(time);
            String label = w.emergency() ? "Аварійне відключення" : "Планові роботи · можливе відключення";
            if (w.start < start) label += " · продовження";
            if (w.overdue(now.toEpochSecond()) && day.equals(now.toLocalDate())) label += " · завершення уточнюється";
            if (!w.reason.isEmpty()) label += "\n" + w.reason;
            ((TextView) row.findViewById(R.id.p_label)).setText(label);
            parent.addView(row);
        }
        parent.setVisibility(parent.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }
}
