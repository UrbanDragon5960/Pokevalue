package com.pokevalue.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CollectionStore(context: Context) {
    private val prefs=context.getSharedPreferences("pokevalue_collection", Context.MODE_PRIVATE)

    fun load(): List<Card> {
        val raw=prefs.getString("cards","[]") ?: "[]"
        val a=JSONArray(raw)
        val out=mutableListOf<Card>()
        for(i in 0 until a.length()) {
            val o=a.getJSONObject(i)
            out.add(
                Card(
                    id=o.optString("id"),
                    name=o.optString("name"),
                    set=o.optString("set"),
                    number=o.optString("number"),
                    image=o.optString("image"),
                    gbp=o.optDouble("gbp",0.0),
                    priceSource=o.optString("priceSource"),
                    rarity=o.optString("rarity"),
                    confidence=o.optInt("confidence",0),
                    quantity=o.optInt("quantity",1)
                )
            )
        }
        return out
    }

    fun save(cards: List<Card>) {
        val a=JSONArray()
        cards.forEach { c ->
            a.put(JSONObject().apply {
                put("id",c.id)
                put("name",c.name)
                put("set",c.set)
                put("number",c.number)
                put("image",c.image)
                put("gbp",c.gbp)
                put("priceSource",c.priceSource)
                put("rarity",c.rarity)
                put("confidence",c.confidence)
                put("quantity",c.quantity)
            })
        }
        prefs.edit().putString("cards",a.toString()).apply()
    }
}
