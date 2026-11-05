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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PokeValueApp() }
    }
}

data class Card(val id:String,val name:String,val set:String,val number:String,val image:String,val gbp:Double,val quantity:Int=1)

class PokeState(context: android.content.Context) {\n    private val store = CollectionStore(context)\n
    var screen by mutableStateOf("collection")
    var cards by mutableStateOf(store.load())
    var results by mutableStateOf(listOf<Card>())
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")
    var ocr by mutableStateOf("")
}

@Composable
fun PokeValueApp() {
    val context = androidx.compose.ui.platform.LocalContext.current\n    val s = remember { PokeState(context.applicationContext) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(bottomBar = {
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
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        preview=b
        if(b!=null) {
            s.ocr="Reading card..."
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(b,0))
                .addOnSuccessListener { r ->
                    s.ocr=r.text
                    query=r.text.lines().firstOrNull { it.trim().length>=3 }?.trim() ?: ""
                }
                .addOnFailureListener { s.ocr="OCR failed. Enter the name manually." }
        }
    }
    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Scan Pokémon card",style=MaterialTheme.typography.headlineMedium)
        Text("Capture the front of the card.")
        Button({camera.launch(null)},Modifier.fillMaxWidth()){Text("Open camera")}
        preview?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().height(240.dp)) }
        Text(s.ocr)
        OutlinedTextField(query,{query=it},label={Text("Card name")},modifier=Modifier.fillMaxWidth())
        Button({searchCards(s,query)},enabled=query.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Find card")}
    }
}

fun searchCards(s:PokeState,q:String) {
    s.loading=true
    s.message=""
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url="https://api.pokemontcg.io/v2/cards?q=name:"+URLEncoder.encode(q,"UTF-8")
            val response=OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            val data=JSONObject(response.body?.string() ?: "{}").optJSONArray("data")
            val found=mutableListOf<Card>()
            if(data!=null) for(i in 0 until minOf(data.length(),20)) {
                val o=data.getJSONObject(i)
                val prices=o.optJSONObject("tcgplayer")?.optJSONObject("prices")
                var usd=0.0
                if(prices!=null) for(type in listOf("holofoil","normal","reverseHolofoil","1stEditionHolofoil","1stEditionNormal")) {
                    val v=prices.optJSONObject(type)?.optDouble("market",0.0) ?: 0.0
                    if(v>usd) usd=v
                }
                found.add(Card(
                    o.optString("id"),
                    o.optString("name"),
                    o.optJSONObject("set")?.optString("name","") ?: "",
                    o.optString("number"),
                    o.optJSONObject("images")?.optString("small","") ?: "",
                    usd*0.79
                ))
            }
            withContext(Dispatchers.Main) {
                s.results=found
                s.loading=false
                s.screen="results"
                if(found.isEmpty()) s.message="No matches found."
            }
        } catch(e:Exception) {
            withContext(Dispatchers.Main) {
                s.loading=false
                s.message="Search failed: "+e.message
            }
        }
    }
}

@Composable
fun Results(s:PokeState) {
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
                    val old=s.cards.find { it.id==c.id }
                    s.cards=if(old==null) s.cards+c else s.cards.map { if(it.id==c.id) it.copy(quantity=it.quantity+1) else it }
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
                Text(if(c.gbp>0) "≈ £"+String.format("%.2f",c.gbp) else "No market price")
            }
            Button(onAdd){Text("Add")}
        }
    }
}

@Composable
fun Collection(s:PokeState) {
    val total=s.cards.sumOf { it.gbp*it.quantity }
    Column(Modifier.padding(16.dp)) {
        Text("PokeValue",style=MaterialTheme.typography.headlineLarge)
        Text("Collection value: £"+String.format("%.2f",total),style=MaterialTheme.typography.titleLarge)
        Text(s.cards.sumOf { it.quantity }.toString()+" cards • "+s.cards.size+" unique")
        Spacer(Modifier.height(12.dp))
        if(s.cards.isEmpty()) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("No cards scanned yet.")
                    Button({s.screen="scan"}){Text("Scan your first card")}
                }
            }
        } else {
            LazyColumn { items(s.cards){ CardRow(it){} } }
        }
    }
}
