package ua.mk.svitlo;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pick Дільниця → Населений пункт → Вулиця → Будинок, same as on the website. */
public class AddAddressActivity extends AppCompatActivity {

    static final class Item {
        final long id;
        final String name;

        Item(long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final String[] ID_KEY = {"idfilial", "idnaspunkt", "idstreet", "iddom"};
    private static final String[] NAME_KEY = {"fullname", "naznaspunkt", "nazstreet", "nazdom"};

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int[] generation = new int[4];
    private final Item[] sel = new Item[4];
    private final MaterialAutoCompleteTextView[] fields = new MaterialAutoCompleteTextView[4];
    private final TextInputLayout[] boxes = new TextInputLayout[4];
    private boolean settingText;

    private EditText etName;
    private Button btnSave;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        fields[0] = findViewById(R.id.ac_fil);
        fields[1] = findViewById(R.id.ac_ns);
        fields[2] = findViewById(R.id.ac_st);
        fields[3] = findViewById(R.id.ac_dom);
        boxes[0] = findViewById(R.id.til_fil);
        boxes[1] = findViewById(R.id.til_ns);
        boxes[2] = findViewById(R.id.til_st);
        boxes[3] = findViewById(R.id.til_dom);
        etName = findViewById(R.id.et_name);
        btnSave = findViewById(R.id.btn_save);
        tvStatus = findViewById(R.id.tv_status);

        for (int i = 0; i < 4; i++) {
            final int lvl = i;
            fields[i].setOnItemClickListener((parent, view, position, id) -> {
                Object o = parent.getItemAtPosition(position);
                if (o instanceof Item) select(lvl, (Item) o);
            });
            fields[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (settingText) return;
                    if (sel[lvl] != null && !s.toString().equals(sel[lvl].name)) {
                        sel[lvl] = null;
                        resetBelow(lvl);
                        updateSave();
                    }
                }
            });
        }
        resetBelow(0);
        load(0, "/api/addr/filii");
        btnSave.setOnClickListener(v -> save());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }

    private void select(int lvl, Item it) {
        sel[lvl] = it;
        resetBelow(lvl);
        if (lvl == 0) load(1, "/api/addr/filii/" + it.id + "/ns");
        else if (lvl == 1) load(2, "/api/addr/ns/" + it.id + "/street");
        else if (lvl == 2) load(3, "/api/addr/street/" + it.id + "/dom");
        updateSave();
    }

    /** Empties and disables every field below the given level. */
    private void resetBelow(int lvl) {
        for (int i = lvl + 1; i < 4; i++) {
            generation[i]++;
            sel[i] = null;
            settingText = true;
            fields[i].setText("", false);
            settingText = false;
            fields[i].setAdapter(new ArrayAdapter<Item>(this, android.R.layout.simple_list_item_1, new ArrayList<>()));
            boxes[i].setEnabled(false);
            boxes[i].setHelperText(null);
        }
        updateSave();
    }

    private void updateSave() {
        btnSave.setEnabled(sel[3] != null);
    }

    private void load(int lvl, String path) {
        generation[lvl]++;
        final int gen = generation[lvl];
        boxes[lvl].setEnabled(false);
        boxes[lvl].setHelperText("Завантаження…");
        bg.execute(() -> {
            try {
                JSONArray arr = Api.getArray(path);
                List<Item> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    items.add(new Item(o.getLong(ID_KEY[lvl]), o.optString(NAME_KEY[lvl]).trim()));
                }
                sort(items, lvl == 2, lvl == 3);
                ui.post(() -> {
                    if (generation[lvl] != gen || isFinishing()) return;
                    fields[lvl].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));
                    boxes[lvl].setEnabled(!items.isEmpty());
                    boxes[lvl].setHelperText(items.isEmpty() ? "Нічого не знайдено" : null);
                    if (items.size() == 1) {
                        settingText = true;
                        fields[lvl].setText(items.get(0).name, false);
                        settingText = false;
                        select(lvl, items.get(0));
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (generation[lvl] != gen || isFinishing()) return;
                    boxes[lvl].setHelperText("Не вдалося завантажити. Перевірте інтернет і відкрийте екран знову.");
                });
            }
        });
    }

    private static void sort(List<Item> items, boolean streets, boolean houses) {
        Collator col = Collator.getInstance(new Locale("uk", "UA"));
        if (houses) {
            items.sort(Comparator.comparingInt((Item i) -> leadingNumber(i.name))
                    .thenComparing(i -> i.name, col));
        } else if (streets) {
            items.sort(Comparator.comparing((Item i) -> withoutPrefix(i.name), col));
        } else {
            items.sort(Comparator.comparing((Item i) -> i.name, col));
        }
    }

    /** "вул. Українська" → "Українська", so streets are sorted by their real name. */
    private static String withoutPrefix(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 && dot <= 8 ? s.substring(dot + 2) : s;
    }

    private static int leadingNumber(String s) {
        int n = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && i < 7) {
            n = n * 10 + (s.charAt(i) - '0');
            i++;
        }
        return i == 0 ? Integer.MAX_VALUE : n;
    }

    private void save() {
        Item ns = sel[1], st = sel[2], dom = sel[3];
        if (ns == null || st == null || dom == null) return;
        Context app = getApplicationContext();
        if (new Store(app).find(dom.id) != null) {
            Toast.makeText(this, "Ця адреса вже є у списку", Toast.LENGTH_LONG).show();
            return;
        }
        String custom = etName.getText() == null ? "" : etName.getText().toString().trim();
        btnSave.setEnabled(false);
        tvStatus.setText("Шукаємо ваші черги…");
        bg.execute(() -> {
            try {
                JSONArray oq = Api.getArray("/api/addr/dom/" + dom.id + "/outage-queue");
                Address a = new Address();
                a.domId = dom.id;
                a.full = ns.name + ", " + st.name + ", " + dom.name;
                a.name = custom.isEmpty() ? st.name + ", " + dom.name : custom;
                a.applyQueues(oq);
                a.queuesCheckedAt = System.currentTimeMillis();

                Store store = new Store(app);
                List<Address> list = store.addresses();
                list.add(a);
                store.saveAddresses(list);
                store.setActiveDom(a.domId);
                Updater.tick(app, true);

                ui.post(() -> {
                    Toast.makeText(app, "Адресу додано. Черги: " + a.queuesText(), Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    btnSave.setEnabled(true);
                    tvStatus.setText("Не вдалося зберегти. Перевірте інтернет і спробуйте ще раз.");
                });
            }
        });
    }
}
