package ua.mk.svitlo;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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
public class AddAddressActivity extends Activity {

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

    interface Loaded {
        void run(List<Item> items);
    }

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int[] generation = new int[4];

    private Spinner spFil, spNs, spSt, spDom;
    private EditText etName;
    private Button btnSave;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);
        spFil = findViewById(R.id.sp_fil);
        spNs = findViewById(R.id.sp_ns);
        spSt = findViewById(R.id.sp_st);
        spDom = findViewById(R.id.sp_dom);
        etName = findViewById(R.id.et_name);
        btnSave = findViewById(R.id.btn_save);
        tvStatus = findViewById(R.id.tv_status);

        clear(spNs, "— спершу оберіть дільницю —");
        clear(spSt, "— спершу оберіть населений пункт —");
        clear(spDom, "— спершу оберіть вулицю —");
        clear(spFil, "Завантаження…");

        load(0, "/api/addr/filii", "idfilial", "fullname", false,
                items -> setItems(spFil, items, "— оберіть дільницю —"));

        spFil.setOnItemSelectedListener(listener(() -> {
            clear(spNs, "— спершу оберіть дільницю —");
            clear(spSt, "— спершу оберіть населений пункт —");
            clear(spDom, "— спершу оберіть вулицю —");
            Item f = selected(spFil);
            if (f != null) {
                load(1, "/api/addr/filii/" + f.id + "/ns", "idnaspunkt", "naznaspunkt", false,
                        items -> setItems(spNs, items, "— оберіть населений пункт —"));
            }
        }));
        spNs.setOnItemSelectedListener(listener(() -> {
            clear(spSt, "— спершу оберіть населений пункт —");
            clear(spDom, "— спершу оберіть вулицю —");
            Item n = selected(spNs);
            if (n != null) {
                load(2, "/api/addr/ns/" + n.id + "/street", "idstreet", "nazstreet", true,
                        items -> setItems(spSt, items, "— оберіть вулицю —"));
            }
        }));
        spSt.setOnItemSelectedListener(listener(() -> {
            clear(spDom, "— спершу оберіть вулицю —");
            Item s = selected(spSt);
            if (s != null) {
                load(3, "/api/addr/street/" + s.id + "/dom", "iddom", "nazdom", false,
                        items -> setItems(spDom, items, "— оберіть будинок —"));
            }
        }));
        spDom.setOnItemSelectedListener(listener(() -> btnSave.setEnabled(selected(spDom) != null)));

        btnSave.setOnClickListener(v -> save());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }

    private AdapterView.OnItemSelectedListener listener(Runnable r) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                r.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                r.run();
            }
        };
    }

    private void load(int level, String path, String idKey, String nameKey, boolean streets, Loaded done) {
        // anything still loading for this level or below is now outdated
        for (int i = level; i < generation.length; i++) generation[i]++;
        final int gen = generation[level];
        tvStatus.setText("Завантаження…");
        bg.execute(() -> {
            try {
                JSONArray arr = Api.getArray(path);
                List<Item> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    items.add(new Item(o.getLong(idKey), o.optString(nameKey).trim()));
                }
                sort(items, streets, "nazdom".equals(nameKey));
                ui.post(() -> {
                    if (generation[level] != gen || isFinishing()) return;
                    tvStatus.setText("");
                    done.run(items);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (generation[level] != gen) return;
                    tvStatus.setText("Не вдалося завантажити. Перевірте інтернет і спробуйте ще раз.");
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

    private void setItems(Spinner sp, List<Item> items, String placeholder) {
        List<Item> all = new ArrayList<>();
        all.add(new Item(0, placeholder));
        all.addAll(items);
        ArrayAdapter<Item> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, all);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setEnabled(!items.isEmpty());
        if (items.isEmpty()) tvStatus.setText("Нічого не знайдено");
    }

    private void clear(Spinner sp, String placeholder) {
        List<Item> one = new ArrayList<>();
        one.add(new Item(0, placeholder));
        ArrayAdapter<Item> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, one);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setEnabled(false);
        if (sp == spDom && btnSave != null) btnSave.setEnabled(false);
    }

    private static Item selected(Spinner sp) {
        Object o = sp.getSelectedItem();
        if (o instanceof Item && ((Item) o).id != 0) return (Item) o;
        return null;
    }

    private void save() {
        Item ns = selected(spNs), st = selected(spSt), dom = selected(spDom);
        if (ns == null || st == null || dom == null) return;
        Context app = getApplicationContext();
        if (new Store(app).find(dom.id) != null) {
            Toast.makeText(this, "Ця адреса вже є у списку", Toast.LENGTH_LONG).show();
            return;
        }
        String custom = etName.getText().toString().trim();
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
