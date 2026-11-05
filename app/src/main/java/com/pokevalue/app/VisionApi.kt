package com.pokevalue.app

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class VisionCard(val id:String,val name:String,val set:String,val number:String,val image:String,val gbp:Double,val confidence:Double,val source:String)

object VisionApi {
    private val client=OkHttpClient()
    fun identify(bitmap:Bitmap,key:String):List<VisionCard> {
        val out=ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG,85,out)
        val body=out.toByteArray().toRequestBody("image/jpeg".toMediaType())
        val request=Request.Builder()
            .url("https://api.pokemontcgapi.com/v1/vision/identify?top_k=5&include=prices")
            .header("X-Api-Key",key)
            .post(body).build()
        val response=client.newCall(request).execute()
        if(!response.isSuccessful) throw IllegalStateException("Vision API HTTP "+response.code)
        val data=JSONObject(response.body?.string()?:"{}").getJSONObject("data")
        val arr=data.optJSONArray("candidates") ?: return emptyList()
        val list=mutableListOf<VisionCard>()
        for(i in 0 until arr.length()){
            val c=arr.getJSONObject(i)
            val eur=c.optDouble("index_eur",0.0)
            list.add(VisionCard(
                c.optString("id"),c.optString("name"),
                c.optJSONObject("set")?.optString("name","")?:"",
                c.optString("number"),c.optString("image_url",""),
                eur*0.86,c.optDouble("confidence",0.0),"PTCG Index"
            ))
        }
        return list
    }
}
