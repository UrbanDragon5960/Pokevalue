package com.pokevalue.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.*

data class Card(val id:String,val name:String,val set:String,val number:String,val image:String,val gbp:Double,val priceSource:String="",val quantity:Int=1)

class PokeState(context: android.content.Context) {
    private val store = CollectionStore(context)
    var screen by mutableStateOf("collection")
    var cards by mutableStateOf(store.load())
    var results by mutableStateOf(listOf<Card>())
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")
    var ocr by mutableStateOf("")
}

@Composable
fun PokeValueApp() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val s=remember { PokeState(context.applicationContext) }
    MaterialTheme(colorScheme=darkColorScheme()) {
        Scaffold(bottomBar={
            NavigationBar {
                NavigationBarItem(s.screen=="collection",{s.screen="collection"},label={Text("Collection")},icon={})
                NavigationBarItem(s.screen=="scan",{s.screen="scan"},label={Text("Scan")},icon={})
            }
        }) { p ->
            Box(Modifier.padding(p).fillMaxSize()) {
                when(s.screen) {
                    "scan" -> Scanner(s)
                    "results" -> Results(s)
                    else -> Collection(s)
                }
            }
        }
    }
}

@Composable
fun Scanner(s:PokeState) {
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var query by remember { mutableStateOf("") }

    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        preview=b
        if(b!=null) {
            s.ocr="Reading card text..."
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(b,0))
                .addOnSuccessListener { r ->
                    s.ocr=if(r.text.isBlank()) "No text detected. Enter the card name." else "Detected: "+r.text.replace("\\n"," ").take(180)
                    if(query.isBlank()) query=r.text.lineSequence().map{it.trim()}.firstOrNull{looksLikeCardName(it)} ?: ""
                }
                .addOnFailureListener { s.ocr="OCR failed. Enter the card name manually." }
        }
    }

    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Scan Pokémon card",style=MaterialTheme.typography.headlineMedium)
        Text("Uses on-device OCR and TCGdex. No API key or paid account is required.")
        Button({camera.launch(null)},Modifier.fillMaxWidth()){Text("Open camera")}
        preview?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().height(240.dp)) }
        if(s.ocr.isNotBlank()) Text(s.ocr)
        OutlinedTextField(query,{query=it},label={Text("Card / Pokémon name")},modifier=Modifier.fillMaxWidth())
        Button({searchCards(s,query)},enabled=query.isNotBlank()&&!s.loading,modifier=Modifier.fillMaxWidth()){Text(if(s.loading) "Searching..." else "Find card")}
        if(s.message.isNotBlank()) Text(s.message)
    }
}

private fun looksLikeCardName(text:String):Boolean {
    val t=text.trim()
    if(t.length<3 || t.length>40) return false
    if(t.count{it.isLetter()}<3) return false
    return !t.all{it.isDigit() || it.isWhitespace() || it in "#/-"}
}

fun searchCards(s:PokeState,q:String) {
    s.loading=true
    s.message=""
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val encoded=URLEncoder.encode(q.trim(),"UTF-8")
            val response=OkHttpClient().newCall(Request.Builder().url("https://api.tcgdex.net/v2/en/cards?name=$encoded").build()).execute()
            if(!response.isSuccessful) throw IllegalStateException("TCGdex HTTP "+response.code)
            val data=JSONObject(response.body?.string()?:"{}")
            val arr=data.optJSONArray("data")
            val found=mutableListOf<Card>()
            if(arr!=null) for(i in 0 until minOf(arr.length(),10)) {
                val id=arr.getJSONObject(i).optString("id")
                if(id.isNotBlank()) fetchCardDetail(id)?.let{found.add(it)}
            }
            withContext(Dispatchers.Main) {
                s.results=found
                s.loading=false
                s.screen="results"
                s.message=if(found.isEmpty()) "No matches found. Try the Pokémon name or collector number." else found.size.toString()+" possible matches found."
            }
        } catch(e:Exception) {
            withContext(Dispatchers.Main) { s.loading=false; s.message="Search failed: "+(e.message ?: "unknown error") }
        }
    }
}

private fun fetchCardDetail(id:String):Card? {
    val safeId=URLEncoder.encode(id,"UTF-8").replace("+","%20")
    val response=OkHttpClient().newCall(Request.Builder().url("https://api.tcgdex.net/v2/en/cards/$safeId").build()).execute()
    if(!response.isSuccessful) return null
    val o=JSONObject(response.body?.string()?:"{}")
    val pricing=o.optJSONObject("pricing")
    val cm=pricing?.optJSONObject("cardmarket")
    val tp=pricing?.optJSONObject("tcgplayer")
    var gbp=0.0
    var source=""
    val eur=cm?.optDouble("avg30",0.0) ?: cm?.optDouble("trend",0.0) ?: 0.0
    if(eur>0) { gbp=eur*EUR_TO_GBP_ESTIMATE; source="Cardmarket 30-day average" }
    else {
        for(v in listOf("normal","reverse-holofoil","holofoil","1st-edition","1st-edition-holofoil","unlimited","unlimited-holofoil")) {
            val usd=tp?.optJSONObject(v)?.optDouble("marketPrice",0.0) ?: 0.0
            if(usd>0) { gbp=usd*USD_TO_GBP_ESTIMATE; source="TCGplayer market estimate"; break }
        }
    }
    val base=o.optString("image")
    return Card(o.optString("id"),o.optString("name"),o.optJSONObject("set")?.optString("name","")?:"",o.optString("localId"),if(base.isNotBlank()) "$base/high.webp" else "",gbp,source)
}

@Composable
fun Results(s:PokeState) {
    val context=androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Card matches",style=MaterialTheme.typography.headlineSmall)
            TextButton({s.screen="scan"}){Text("Back")}
        }
        if(s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.message.isNotBlank()) Text(s.message)
        LazyColumn {
            items(s.results) { c ->
                CardRow(c) {
                    val old=s.cards.find{it.id==c.id}
                    s.cards=if(old==null) s.cards+c else s.cards.map{if(it.id==c.id) it.copy(quantity=it.quantity+1) else it}
                    CollectionStore(context.applicationContext).save(s.cards)
                    s.screen="collection"
                }
            }
        }
    }
}

@Composable
fun CardRow(c:Card,onAdd:()->Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical=6.dp)) {
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
            if(c.image.isNotBlank()) AsyncImage(c.image,c.name,Modifier.size(80.dp))
            Column(Modifier.padding(10.dp).weight(1f)) {
                Text(c.name,style=MaterialTheme.typography.titleMedium)
                Text(c.set+" • #"+c.number)
                Text(if(c.gbp>0) "≈ £"+String.format("%.2f",c.gbp) else "No current price data")
                if(c.priceSource.isNotBlank()) Text(c.priceSource,style=MaterialTheme.typography.bodySmall)
            }
            Button(onAdd){Text("Add")}
        }
    }
}

@Composable
fun Collection(s:PokeState) {
    val total=s.cards.sumOf{it.gbp*it.quantity}
    Column(Modifier.padding(16.dp)) {
        Text("PokeValue",style=MaterialTheme.typography.headlineLarge)
        Text("Collection value: £"+String.format("%.2f",total),style=MaterialTheme.typography.titleLarge)
        Text(s.cards.sumOf{it.quantity}.toString()+" cards • "+s.cards.size+" unique")
        Spacer(Modifier.height(12.dp))
        if(s.cards.isEmpty()) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("No cards scanned yet.")
                    Button({s.screen="scan"}){Text("Scan your first card")}
                }
            }
        } else {
            LazyColumn { items(s.cards){CardRow(it){}} }
        }
    }
}

private const val EUR_TO_GBP_ESTIMATE=0.86
private const val USD_TO_GBP_ESTIMATE=0.79
