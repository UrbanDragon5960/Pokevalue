package com.pokevalue.app

import android.content.Context

class SettingsStore(context:Context) {
    private val prefs=context.getSharedPreferences("pokevalue_settings",Context.MODE_PRIVATE)
    fun apiKey():String=prefs.getString("ptcg_api_key","") ?: ""
    fun saveApiKey(key:String){prefs.edit().putString("ptcg_api_key",key.trim()).apply()}
}
