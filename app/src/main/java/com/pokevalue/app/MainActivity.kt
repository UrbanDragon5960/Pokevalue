package com.pokevalue.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.*

data class Card(val id:String,val name:String,val set:String,val number:String,val image:String,val gbp:Double,val priceSource:String="",val rarity:String="",val confidence:Int=0,val quantity:Int=1)

class PokeState(context: Context) {
    private val store = CollectionStore(context)
    var screen by mutableStateOf("collection")
    var selectedCard by mutableStateOf<Card?>(null)
    var cards by mutableStateOf(store.load())
    var results by mutableStateOf(listOf<Card>())
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")
    var ocr by mutableStateOf("")
    var scanHint by mutableStateOf("")
    fun addCard(card: Card) {
        val existing = cards.indexOfFirst { it.id == card.id }
        cards = if (existing < 0) cards + card else cards.mapIndexed { index, old ->
            if (index == existing) old.copy(quantity = old.quantity + 1,
                gbp = if (old.gbp > 0.0) old.gbp else card.gbp,
                priceSource = if (old.priceSource.isNotBlank()) old.priceSource else card.priceSource)
            else old
        }
        store.save(cards)
        message = "${card.name} added to your collection."
        screen = "collection"
    }
}

@Composable
fun PokeValueApp() {
    val context = LocalContext.current
    val s = remember { PokeState(context.applicationContext) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF65D8C6),
        secondary = androidx.compose.ui.graphics.Color(0xFF8EA9FF),
        background = androidx.compose.ui.graphics.Color(0xFF0A1020),
        surface = androidx.compose.ui.graphics.Color(0xFF151F33),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202D45)
    )) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(s.screen == "collection", { s.screen = "collection"; s.message = "" }, label = { Text("Collection") }, icon = {})
                    NavigationBarItem(s.screen == "scan" || s.screen == "results", { s.screen = "scan"; s.message = "" }, label = { Text("Scan cards") }, icon = {})
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (s.screen) {
                    "scan" -> Scanner(s)
                    "results" -> Results(s)
                    "detail" -> s.selectedCard?.let { CardDetail(it) { s.screen = "collection" } } ?: Collection(s)
                    else -> Collection(s)
                }
            }
        }
    }
}

private fun createCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "card-captures").apply { mkdirs() }
    val file = File.createTempFile("pokevalue-card-", ".jpg", dir)
    return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}

@Composable
fun Scanner(s: PokeState) {
    val context = LocalContext.current
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri
        if (saved && uri != null) {
            s.ocr = "Reading the full-resolution image..."
            try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(InputImage.fromFilePath(context, uri))
                    .addOnSuccessListener { recognised ->
                        val raw = recognised.text
                        val bestName = extractBestName(recognised)
                        val number = extractCollectorNumber(raw)
                        s.scanHint = raw
                        s.ocr = when {
                            raw.isBlank() -> "No readable text found. Try brighter, even lighting and keep the card flat."
                            bestName.isBlank() -> "I read some text, but couldn't confidently find the Pokémon name. Enter the name below; recognised text is available for checking."
                            else -> "Suggested name: $bestName  •  Card number: ${number.ifBlank { "not found" }}. Check it before searching."
                        }
                        if (bestName.isNotBlank()) query = bestName
                    }
                    .addOnFailureListener { s.ocr = "Couldn't read this image. Try again in brighter, even lighting." }
            } catch (e: Exception) {
                s.ocr = "Couldn't open the captured image. Please try again."
            }
        } else {
            s.ocr = "Camera capture cancelled."
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Scan a card", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Capture the whole card in focus. PokeValue reads the printed text, then checks matching card records.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        try {
                            val uri = createCaptureUri(context)
                            captureUri = uri
                            camera.launch(uri)
                        } catch (e: Exception) {
                            s.ocr = "Camera couldn't start: ${e.message ?: "please try again"}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Open camera and capture card") }
                Text("Recognition tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("• Keep the card flat and fill most of the frame\n• Avoid glare from lights or sleeves\n• Make sure the name and collector number are sharp", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.ocr.isNotBlank()) Text(s.ocr, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("Search manually", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Card name") }, singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            Button(
                onClick = { searchCards(s, query, s.scanHint) },
                enabled = query.isNotBlank() && !s.loading,
                modifier = Modifier.height(56.dp),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) { Text(if (s.loading) "..." else "Find card") }
        }
        Text("OCR is a helper, not a guaranteed card identifier. Always check the set and card number in the results.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.message.isNotBlank()) Text(s.message, color = MaterialTheme.colorScheme.error)
    }
}

private fun extractCollectorNumber(text: String): String {
    val patterns = listOf(
        Regex("""\b\d{1,3}/\d{1,3}\b"""),
        Regex("""\b[A-Z]{1,4}\d{1,3}\b"""),
        Regex("""\b\d{1,3}\b""")
    )
    val lines = text.lines().map { it.trim().uppercase() }
    for (pattern in patterns) {
        val found = lines.asSequence().flatMap { pattern.findAll(it).map { m -> m.value } }.firstOrNull()
        if (found != null) return found
    }
    return ""
}

private fun extractBestName(text: com.google.mlkit.vision.text.Text): String {
    val blocked = listOf("BASIC", "STAGE", "HP", "WEAKNESS", "RESISTANCE", "RETREAT", "ILLUSTRATOR", "TRAINER", "ENERGY", "POKEMON", "POKÉMON", "RULE", "RULES")
    val lines = text.textBlocks.flatMap { it.lines }
    val imageBottom = lines.mapNotNull { it.boundingBox?.bottom }.maxOrNull()?.toFloat() ?: 1f
    val candidates = lines.mapNotNull { line ->
        val value = line.text.trim().replace(Regex("""\s+"""), " ")
        val top = line.boundingBox?.top?.toFloat() ?: imageBottom
        val yRatio = if (imageBottom > 0f) top / imageBottom else 1f
        if (value.length in 3..30 && value.count { it.isLetter() } >= 3 &&
            value.none { it.isDigit() } && blocked.none { value.equals(it, true) } &&
            !value.contains("©") && !value.contains("www", true)) value to yRatio else null
    }
    return candidates.filter { it.second <= 0.42f }
        .maxByOrNull { (line, y) -> scoreNameLine(line) + ((0.42f - y) * 35f).toInt() }
        ?.first ?: ""
}
private fun scoreNameLine(line: String): Int {
    var score = line.length.coerceAtMost(24)
    if (line.firstOrNull()?.isUpperCase() == true) score += 8
    if (line.any { it.isLowerCase() }) score += 5
    if (line.contains(" ex", true) || line.endsWith(" V") || line.endsWith(" VMAX")) score += 3
    if (line.contains("GX") || line.contains("VSTAR")) score += 2
    return score
}

private fun rankCandidates(cards: List<Card>, query: String, hint: String): List<Card> {
    val number = extractCollectorNumber(hint)
    val q = normalise(query)
    return cards.map { card ->
        var score = 0
        val name = normalise(card.name)
        if (name == q) score += 65 else if (name.contains(q) || q.contains(name)) score += 48
        score += (tokenOverlap(q, name) * 20).coerceAtMost(20)
        if (number.isNotBlank() && normalise(card.number) == normalise(number)) score += 35
        if (card.set.isNotBlank() && hint.contains(card.set, true)) score += 10
        card.copy(confidence = score.coerceIn(1, 99))
    }.sortedByDescending { it.confidence }
}

private fun normalise(s: String) = s.lowercase().replace("[^a-z0-9]".toRegex(), "")
private fun tokenOverlap(a: String, b: String): Int {
    val aa = a.chunked(3).toSet()
    val bb = b.chunked(3).toSet()
    return aa.intersect(bb).size.coerceAtMost(5)
}

fun searchCards(s: PokeState, q: String, hint: String = "") {
    val cleanQuery = q.trim()
    if (cleanQuery.isBlank()) {
        s.message = "Enter a Pokémon or card name first."
        return
    }
    if (s.loading) return
    s.loading = true
    s.results = emptyList()
    s.message = "Searching TCGdex for \"$cleanQuery\"…"
    s.screen = "results"
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://api.tcgdex.net/v2/en/cards?name=$encoded&pagination:itemsPerPage=20"
            val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) throw IllegalStateException("Card database returned HTTP ${response.code}. Please try again.")
            val body = response.body?.string().orEmpty()
            val arr = try { org.json.JSONArray(body) } catch (_: org.json.JSONException) {
                JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
            }
            val ids = (0 until minOf(arr.length(), 20)).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }.distinct()
            val found = coroutineScope {
                ids.map { id -> async(Dispatchers.IO) { runCatching { fetchCardDetail(id) }.getOrNull() } }
                    .awaitAll().filterNotNull()
            }
            val ranked = rankCandidates(found, cleanQuery, hint)
            withContext(Dispatchers.Main) {
                s.results = ranked
                s.loading = false
                s.message = if (ranked.isEmpty()) "No matches found. Try the Pokémon name only, or type a shorter part of the name."
                    else "${ranked.size} matches found. Check the artwork, set and card number before adding."
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                s.loading = false
                s.message = "Search failed: ${e.message ?: "Please check your internet connection and try again."}"
            }
        }
    }
}
private fun positivePrice(obj: JSONObject?, vararg keys: String): Double {
    if (obj == null) return 0.0
    for (key in keys) {
        val value = obj.optDouble(key, 0.0)
        if (value.isFinite() && value > 0.0) return value
    }
    return 0.0
}

private fun priceFromPricing(pricing: JSONObject?): Pair<Double, String> {
    if (pricing == null) return 0.0 to ""
    val cm = pricing.optJSONObject("cardmarket")
    val eur = positivePrice(cm, "avg30", "avg7", "avg1", "avg", "trend", "low")
    if (eur > 0.0) return (eur * EUR_TO_GBP_ESTIMATE) to "Cardmarket estimate (EUR converted to GBP)"
    val tp = pricing.optJSONObject("tcgplayer")
    val variants = listOf("normal", "reverse-holofoil", "holofoil", "1st-edition", "1st-edition-holofoil", "unlimited", "unlimited-holofoil")
    for (variant in variants) {
        val usd = positivePrice(tp?.optJSONObject(variant), "marketPrice", "midPrice", "lowPrice")
        if (usd > 0.0) return (usd * USD_TO_GBP_ESTIMATE) to "TCGplayer market estimate (USD converted to GBP)"
    }
    return 0.0 to ""
}

private fun fetchCardDetail(id: String): Card? {
    val safeId = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    val response = OkHttpClient().newCall(Request.Builder().url("https://api.tcgdex.net/v2/en/cards/$safeId").build()).execute()
    if (!response.isSuccessful) return null
    val o = JSONObject(response.body?.string() ?: "{}")
    var (gbp, source) = priceFromPricing(o.optJSONObject("pricing"))
    if (gbp <= 0.0) {
        val detailed = o.optJSONArray("variants_detailed")
        if (detailed != null) for (i in 0 until detailed.length()) {
            val variant = detailed.optJSONObject(i) ?: continue
            val priced = priceFromPricing(variant.optJSONObject("pricing"))
            if (priced.first > 0.0) { gbp = priced.first; source = priced.second; break }
        }
    }
    val base = o.optString("image")
    val setObj = o.optJSONObject("set")
    return Card(o.optString("id"), o.optString("name"), setObj?.optString("name", "") ?: "", o.optString("localId"), if (base.isNotBlank()) "$base/high.webp" else "", gbp, source, o.optString("rarity", ""))
}

@Composable
fun Results(s: PokeState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Choose your card", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton({ s.screen = "scan" }) { Text("Back") }
        }
        Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.results, key = { it.id }) { card ->
                CardRow(card, onAdd = { s.addCard(card) })
            }
        }
    }
}

@Composable
fun CardRow(c: Card, onAdd: () -> Unit, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.image.isNotBlank()) AsyncImage(c.image, c.name, Modifier.size(if (compact) 76.dp else 92.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(c.set + "  •  #" + c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.rarity.isNotBlank()) Text(c.rarity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!compact) Text(if (c.gbp > 0) "£" + String.format("%.2f", c.gbp) else "Price unavailable", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (!compact && c.confidence > 0) Text("Name match: ${c.confidence}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (compact) Text("Tap to view value" + if (c.quantity > 1) "  •  Qty ${c.quantity}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!compact) Button(onAdd, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Add") }
        }
    }
}


@Composable
fun CardDetail(card: Card, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Collection") }
            Spacer(Modifier.weight(1f))
            Text("CARD DETAILS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (card.image.isNotBlank()) {
                    AsyncImage(
                        model = card.image,
                        contentDescription = card.name,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp)
                    )
                }
                Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    listOf(card.set, if (card.number.isNotBlank()) "#${card.number}" else "").filter { it.isNotBlank() }.joinToString("  •  "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (card.rarity.isNotBlank()) Text(card.rarity, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ESTIMATED CARD VALUE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (card.gbp > 0.0) "£" + String.format("%.2f", card.gbp) else "Price unavailable",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (card.priceSource.isNotBlank()) Text(card.priceSource, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Quantity owned: ${card.quantity}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to collection") }
    }
}

@Composable
private fun CollectionCardTile(card: Card, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (card.image.isNotBlank()) {
                AsyncImage(
                    model = card.image,
                    contentDescription = card.name,
                    modifier = Modifier.fillMaxWidth().height(190.dp)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(190.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("No card image", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(card.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                if (card.quantity > 1) "Qty ${card.quantity}  •  Tap for details" else "Tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Collection(s: PokeState) {
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Value") }
    val total = s.cards.sumOf { it.gbp * it.quantity }
    val count = s.cards.sumOf { it.quantity }
    val filtered = s.cards.filter { it.name.contains(filter, true) || it.set.contains(filter, true) }
        .let { list -> when (sort) {
            "Name" -> list.sortedBy { it.name.lowercase() }
            "Quantity" -> list.sortedByDescending { it.quantity }
            else -> list.sortedByDescending { it.gbp * it.quantity }
        } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("POKEVALUE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Your collection", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { s.screen = "scan"; s.message = "" }) { Text("+ Add cards") }
        }
        Spacer(Modifier.height(12.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ESTIMATED COLLECTION VALUE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("£" + String.format("%.2f", total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column { Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Total cards", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column { Text(s.cards.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Unique cards", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (s.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(s.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search collection") }, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.width(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text("Sort") }
                DropdownMenu(expanded, { expanded = false }) {
                    listOf("Value", "Name", "Quantity").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { sort = option; expanded = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (s.cards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your collection starts here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Scan a Pokémon card to identify it and add it to your collection.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ s.screen = "scan" }) { Text("Scan your first card") }
                }
            }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No cards match that search.") }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(filtered, key = { it.id }) { card ->
                    CollectionCardTile(card) { s.selectedCard = card; s.screen = "detail" }
                }
            }
        }
    }
}

private const val EUR_TO_GBP_ESTIMATE = 0.86
private const val USD_TO_GBP_ESTIMATE = 0.79

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PokeValueApp() }
    }
}
