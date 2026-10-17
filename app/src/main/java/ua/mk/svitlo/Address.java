package ua.mk.svitlo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A saved address and the outage queues it belongs to. */
final class Address {
    long domId;
    String name = "";
    String full = "";
    int gpvId, gavId, sgavId;          // 0 = address is not in such a queue
    String gpvName = "", gavName = "", sgavName = "";
    boolean notify = true;
    long queuesCheckedAt;
    /** House whose queues are used. Usually the same house; a neighbour (36 for 36А)
     *  when the website has no queues for this exact house. */
    long qDomId;
    String qDomName = "";

    long queueSource() {
        return qDomId > 0 ? qDomId : domId;
    }

    boolean hasQueues() {
        return gpvId > 0 || gavId > 0 || sgavId > 0;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("dom", domId);
            o.put("name", name);
            o.put("full", full);
            o.put("gpv", gpvId);
            o.put("gpvN", gpvName);
            o.put("gav", gavId);
            o.put("gavN", gavName);
            o.put("sgav", sgavId);
            o.put("sgavN", sgavName);
            o.put("notify", notify);
            o.put("qAt", queuesCheckedAt);
            o.put("qDom", qDomId);
            o.put("qDomN", qDomName);
        } catch (JSONException ignored) {
        }
        return o;
    }

    static Address fromJson(JSONObject o) {
        Address a = new Address();
        a.domId = o.optLong("dom");
        a.name = o.optString("name");
        a.full = o.optString("full");
        a.gpvId = o.optInt("gpv");
        a.gpvName = o.optString("gpvN");
        a.gavId = o.optInt("gav");
        a.gavName = o.optString("gavN");
        a.sgavId = o.optInt("sgav");
        a.sgavName = o.optString("sgavN");
        a.notify = o.optBoolean("notify", true);
        a.queuesCheckedAt = o.optLong("qAt");
        a.qDomId = o.optLong("qDom");
        a.qDomName = o.optString("qDomN");
        return a;
    }

    /** Reads the answer of /api/addr/dom/{id}/outage-queue */
    void applyQueues(JSONArray arr) {
        gpvId = gavId = sgavId = 0;
        gpvName = gavName = sgavName = "";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            JSONObject outage = item.optJSONObject("outage");
            if (outage == null) continue;
            JSONObject type = item.optJSONObject("outageType");
            int typeId = type != null ? type.optInt("id", outage.optInt("type_id")) : outage.optInt("type_id");
            int id = outage.optInt("id");
            String n = outage.optString("name");
            if (typeId == 1) {
                gavId = id;
                gavName = n;
            } else if (typeId == 2) {
                sgavId = id;
                sgavName = n;
            } else if (typeId == 3) {
                gpvId = id;
                gpvName = n;
            }
        }
    }

    String queuesText() {
        List<String> parts = new ArrayList<>();
        if (gpvId > 0) parts.add("ГПВ " + gpvName);
        if (gavId > 0) parts.add("ГАВ " + gavName);
        if (sgavId > 0) parts.add("СГАВ " + sgavName);
        if (parts.isEmpty()) return "черги не знайдено";
        String s = String.join(" · ", parts);
        if (qDomId > 0 && qDomId != domId) s += " (як у будинку " + qDomName + ")";
        return s;
    }
}
