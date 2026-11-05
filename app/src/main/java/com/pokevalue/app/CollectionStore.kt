package com.pokevalue.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CollectionStore(context: Context) {
    private val prefs=context.getSharedPreferences("pokevalue_collection", Context.MODE_PRIVATE)
    fun load(): List<Card> {
        val raw=prefs.getString("cards","[]") ?: "[]"
        val a=JSONArray(raw); val out=mutableListOf<Card>()
        for(i in 0 until a.length()) { val o=a.getJSONObject(i); out.add(Card(o.optString("id"),o.optString("name"),o.optString("set"),o.optString("number"),o.optString("image"),o.optDouble("gbp",0.0),o.optInt("quantity",1))) }
        return out
    }
    fun save(cards: List<Card>) {
        val a=JSONArray(); cards.forEach { c -> a.put(JSONObject().apply { put("id",c.id); put("name",c.name); put("set",c.set); put("number",c.number); put("image",c.image); put("gbp",c.gbp); put("quantity",c.quantity) }) }
        prefs.edit().putString("cards",a.toString()).apply()
    }
}
