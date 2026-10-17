package ua.mk.svitlo;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.List;

/** Opens when a widget is placed (and from "Налаштувати" on Android 12+): pick address and text size. */
public class WidgetConfigActivity extends AppCompatActivity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private RadioGroup addresses;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);   // closing without "Готово" = don't add the widget
        setContentView(R.layout.activity_widget_config);
        View content = findViewById(R.id.cfg_content);
        Bars.apply(this, findViewById(R.id.cfg_root), bottom -> Bars.padBottom(content, bottom));

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        addresses = findViewById(R.id.cfg_addresses);
        Store st = new Store(this);
        ((RadioGroup) findViewById(R.id.cfg_size)).check(st.widgetLarge(widgetId) ? R.id.cfg_large : R.id.cfg_normal);
        findViewById(R.id.cfg_add).setOnClickListener(v -> startActivity(new Intent(this, AddAddressActivity.class)));
        findViewById(R.id.cfg_done).setOnClickListener(v -> done());
    }

    @Override
    protected void onResume() {
        super.onResume();
        fillAddresses();
    }

    private void fillAddresses() {
        Store st = new Store(this);
        List<Address> list = st.addresses();
        Address current = st.widgetAddress(widgetId);
        addresses.removeAllViews();
        int checkId = View.NO_ID;
        for (Address a : list) {
            MaterialRadioButton rb = new MaterialRadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(a.domId);
            rb.setText(a.name + "\n" + a.full);
            rb.setTextSize(18);
            rb.setMinHeight((int) (64 * getResources().getDisplayMetrics().density));
            addresses.addView(rb);
            if (current != null && current.domId == a.domId) checkId = rb.getId();
        }
        if (checkId == View.NO_ID && addresses.getChildCount() > 0) checkId = addresses.getChildAt(0).getId();
        if (checkId != View.NO_ID) addresses.check(checkId);
        findViewById(R.id.cfg_empty).setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.cfg_done).setEnabled(!list.isEmpty());
    }

    private void done() {
        View checked = addresses.findViewById(addresses.getCheckedRadioButtonId());
        if (checked == null || !(checked.getTag() instanceof Long)) return;
        Store st = new Store(this);
        st.setWidgetAddress(widgetId, (Long) checked.getTag());
        st.setWidgetLarge(widgetId, ((RadioGroup) findViewById(R.id.cfg_size)).getCheckedRadioButtonId() == R.id.cfg_large);
        PowerWidget.updateOne(getApplicationContext(), widgetId);
        Updater.ensurePeriodic(getApplicationContext());
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }
}
