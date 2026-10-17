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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    /** Houses of the chosen street (to suggest a neighbour when a house has no queues). */
    private List<Item> houses = new ArrayList<>();
    /** When editing: the address being replaced (0 = adding a new one). */
    private long replaceDom;
    /** When editing: what to pre-select in each field once its list has loaded (by id or by name). */
    private final long[] pendingId = new long[4];
    private final String[] pendingName = new String[4];
    private final List<List<Item>> loaded = new ArrayList<>(java.util.Collections.nCopies(4, (List<Item>) null));

    static final String EXTRA_REPLACE = "replace_dom";

    private EditText etName;
    private Button btnSave;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);
        android.view.View content = findViewById(R.id.add_content);
        Bars.apply(this, findViewById(R.id.add_root), bottom -> Bars.padBottom(content, bottom));
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        replaceDom = getIntent().getLongExtra(EXTRA_REPLACE, 0);

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
        Address old = replaceDom > 0 ? new Store(this).find(replaceDom) : null;
        if (replaceDom > 0) {
            toolbar.setTitle("Редагувати адресу");
            btnSave.setText("Зберегти зміни");
        }
        if (old != null) {
            // Start from the current address: every field is filled in, change only what you need
            etName.setText(old.name);
            pendingId[0] = old.filId;
            pendingId[1] = old.nsId;
            pendingId[2] = old.stId;
            pendingId[3] = old.domId;
            pendingName[1] = old.nsName.isEmpty() ? null : old.nsName;
            pendingName[2] = old.stName.isEmpty() ? null : old.stName;
            pendingName[3] = old.domName.isEmpty() ? null : old.domName;
            tvStatus.setText("Змініть те, що потрібно, і натисніть «Зберегти зміни».");
        }
        resetBelow(0);
        load(0, "/api/addr/filii");
        if (old != null && old.filId == 0 && !old.nsName.isEmpty()) findDistrict(old.nsName);
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
                    if (lvl == 3) houses = items;
                    loaded.set(lvl, items);
                    fields[lvl].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));
                    boxes[lvl].setEnabled(!items.isEmpty());
                    boxes[lvl].setHelperText(items.isEmpty() ? "Нічого не знайдено" : null);
                    if (applyPending(lvl)) return;
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

    /** Editing: pick the remembered choice in this field (which then loads the next field). */
    private boolean applyPending(int lvl) {
        List<Item> items = loaded.get(lvl);
        if (items == null || (pendingId[lvl] == 0 && pendingName[lvl] == null)) return false;
        Item match = null;
        for (Item it : items) {
            if ((pendingId[lvl] > 0 && it.id == pendingId[lvl])
                    || (pendingName[lvl] != null && it.name.equals(pendingName[lvl]))) {
                match = it;
                break;
            }
        }
        pendingId[lvl] = 0;
        pendingName[lvl] = null;
        if (match == null) {
            for (int i = lvl + 1; i < 4; i++) {
                pendingId[i] = 0;
                pendingName[i] = null;
            }
            return false;
        }
        settingText = true;
        fields[lvl].setText(match.name, false);
        settingText = false;
        select(lvl, match);
        return true;
    }

    /** Older saved addresses don't remember their дільниця: find it by the settlement name. */
    private void findDistrict(String nsName) {
        bg.execute(() -> {
            try {
                JSONArray fil = Api.getArray("/api/addr/filii");
                for (int i = 0; i < fil.length(); i++) {
                    long filId = fil.getJSONObject(i).getLong("idfilial");
                    JSONArray ns = Api.getArray("/api/addr/filii/" + filId + "/ns");
                    for (int j = 0; j < ns.length(); j++) {
                        JSONObject o = ns.getJSONObject(j);
                        if (nsName.equals(o.optString("naznaspunkt").trim())) {
                            long nsId = o.getLong("idnaspunkt");
                            ui.post(() -> {
                                if (sel[0] != null || isFinishing()) return;
                                pendingId[0] = filId;
                                pendingId[1] = nsId;
                                applyPending(0);
                            });
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
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

    private static String queuePath(long domId) {
        return "/api/addr/dom/" + domId + "/outage-queue";
    }

    /** Houses with a close number (36 for 36А, then 35, 37…), best first. */
    private static List<Item> neighbours(Item dom, List<Item> all) {
        List<Item> r = new ArrayList<>();
        int n = leadingNumber(dom.name);
        if (n == Integer.MAX_VALUE) return r;
        for (Item h : all) {
            int hn = leadingNumber(h.name);
            if (h.id != dom.id && hn != Integer.MAX_VALUE && Math.abs(hn - n) <= 2) r.add(h);
        }
        String plain = String.valueOf(n);
        r.sort(Comparator.comparingInt((Item h) -> Math.abs(leadingNumber(h.name) - n))
                .thenComparingInt(h -> h.name.equals(plain) ? 0 : 1)
                .thenComparingInt(h -> h.name.length()));
        return r.size() > 4 ? new ArrayList<>(r.subList(0, 4)) : r;
    }

    private void saveFailed(String msg) {
        btnSave.setEnabled(sel[3] != null);
        tvStatus.setText(msg);
    }

    private void save() {
        Item ns = sel[1], st = sel[2], dom = sel[3];
        if (ns == null || st == null || dom == null) return;
        Context app = getApplicationContext();
        if (dom.id != replaceDom && new Store(app).find(dom.id) != null) {
            Toast.makeText(this, "Ця адреса вже є у списку", Toast.LENGTH_LONG).show();
            return;
        }
        String custom = etName.getText() == null ? "" : etName.getText().toString().trim();
        List<Item> near = neighbours(dom, houses);
        btnSave.setEnabled(false);
        tvStatus.setText("Шукаємо ваші черги…");
        bg.execute(() -> {
            try {
                JSONArray oq = Api.getArray(queuePath(dom.id));
                if (oq.length() > 0) {
                    finishSave(ns, st, dom, custom, oq, null);
                    return;
                }
                // The website has no queues for this exact house: try the neighbours
                for (Item n : near) {
                    JSONArray q2 = Api.getArray(queuePath(n.id));
                    if (q2.length() > 0) {
                        ui.post(() -> askNeighbour(ns, st, dom, custom, n, q2));
                        return;
                    }
                }
                ui.post(() -> {
                    saveFailed("");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Черги не знайдено")
                            .setMessage("На сайті Миколаївобленерго для будинку «" + dom.name
                                    + "» не вказано черг, тому графік показати неможливо.\n\n"
                                    + "Спробуйте сусідній номер будинку.")
                            .setPositiveButton("Зрозуміло", null)
                            .show();
                });
            } catch (Exception e) {
                ui.post(() -> saveFailed("Не вдалося зберегти. Перевірте інтернет і спробуйте ще раз."));
            }
        });
    }

    private void askNeighbour(Item ns, Item st, Item dom, String custom, Item n, JSONArray q2) {
        Address tmp = new Address();
        tmp.applyQueues(q2);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Черги для «" + dom.name + "» не вказано")
                .setMessage("На сайті Миколаївобленерго для будинку «" + dom.name + "» немає черг.\n\n"
                        + "У сусіднього будинку «" + n.name + "»: " + tmp.queuesText()
                        + ". Найімовірніше, у вас така сама.\n\n"
                        + "Використати черги будинку «" + n.name + "»?")
                .setPositiveButton("Так, використати", (d, w) ->
                        bg.execute(() -> finishSave(ns, st, dom, custom, q2, n)))
                .setNegativeButton("Обрати інший будинок", (d, w) -> saveFailed(""))
                .setOnCancelListener(d -> saveFailed(""))
                .show();
    }

    /** "Адреса 3" for the third address added without a name (a free number if that one is taken). */
    private static String defaultName(List<Address> list) {
        for (int n = list.size() + 1; ; n++) {
            String name = "Адреса " + n;
            boolean taken = false;
            for (Address x : list) if (name.equalsIgnoreCase(x.name)) taken = true;
            if (!taken) return name;
        }
    }

    /** Runs in the background: saves (or replaces) the address and closes the screen. */
    private void finishSave(Item ns, Item st, Item dom, String custom, JSONArray oq, Item from) {
        Context app = getApplicationContext();
        Address a = new Address();
        a.domId = dom.id;
        a.full = ns.name + ", " + st.name + ", " + dom.name;
        a.name = custom;
        a.applyQueues(oq);
        a.queuesCheckedAt = System.currentTimeMillis();
        a.filId = sel[0] != null ? sel[0].id : 0;
        a.nsId = ns.id;
        a.stId = st.id;
        a.nsName = ns.name;
        a.stName = st.name;
        a.domName = dom.name;
        if (from != null) {
            a.qDomId = from.id;
            a.qDomName = from.name;
        }

        Store store = new Store(app);
        List<Address> list = store.addresses();
        boolean replaced = false;
        if (replaceDom > 0) {
            for (int i = 0; i < list.size(); i++) {
                Address old = list.get(i);
                if (old.domId == replaceDom) {
                    a.notify = old.notify;
                    if (custom.isEmpty()) a.name = old.name;
                    list.set(i, a);
                    store.replaceDomRefs(old.domId, a.domId);
                    replaced = true;
                    break;
                }
            }
        }
        if (!replaced) {
            if (a.name.isEmpty()) a.name = defaultName(list);
            list.add(a);
        }
        store.saveAddresses(list);
        store.setActiveDom(a.domId);
        Updater.tick(app, true);

        String msg = (replaced ? "Адресу змінено. " : "Адресу додано. ") + "Черги: " + a.queuesText();
        ui.post(() -> {
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
