package com.pokevalue.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PokeValueApp() {
    val context = LocalContext.current
    val api = remember { SupabaseClient(context.applicationContext) }
    var session by remember { mutableStateOf(api.restoreSession()) }
    var authMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (session == null) {
        AuthScreen(
            api = api,
            message = authMessage,
            onSignedIn = { session = it; authMessage = "" },
            onMessage = { authMessage = it }
        )
    } else {
        val activeSession = session!!
        val state = remember(activeSession.userId) {
            PokeState(context.applicationContext, api, activeSession)
        }
        var loadingCloud by remember(activeSession.userId) { mutableStateOf(true) }

        LaunchedEffect(activeSession.userId) {
            loadingCloud = true
            try {
                var remote = withContext(Dispatchers.IO) { api.loadCollection(activeSession) }
                val migrationPrefs = context.getSharedPreferences("pokevalue_migration", 0)
                val alreadyMigrated = migrationPrefs.getBoolean("legacy_collection_migrated", false)
                if (remote.isEmpty() && !alreadyMigrated) {
                    val oldCards = state.legacyCards()
                    if (oldCards.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            oldCards.forEach { api.saveCard(activeSession, it) }
                            remote = api.loadCollection(activeSession)
                        }
                    }
                    migrationPrefs.edit().putBoolean("legacy_collection_migrated", true).apply()
                }
                state.replaceCards(remote)
                state.message = if (remote.isEmpty()) "Your cloud collection is ready. Add your first card." else "Collection synced."
            } catch (e: Exception) {
                state.message = "Couldn't load your cloud collection: ${e.message ?: "Check your connection."}"
            } finally {
                loadingCloud = false
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF65D8C6),
            secondary = androidx.compose.ui.graphics.Color(0xFF8EA9FF),
            background = androidx.compose.ui.graphics.Color(0xFF0A1020),
            surface = androidx.compose.ui.graphics.Color(0xFF151F33),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202D45)
        )) {
            Box(Modifier.fillMaxSize()) {
                PokeValueMain(state) {
                    state.clearCollection()
                    session = null
                    authMessage = "You've signed out."
                    scope.launch { withContext(Dispatchers.IO) { api.signOut() } }
                }
                if (loadingCloud) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(18.dp),
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator()
                                Text("Loading your private collection…")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(
    api: SupabaseClient,
    message: String,
    onSignedIn: (SupabaseSession) -> Unit,
    onMessage: (String) -> Unit
) {
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF65D8C6),
        secondary = androidx.compose.ui.graphics.Color(0xFF8EA9FF),
        background = androidx.compose.ui.graphics.Color(0xFF0A1020),
        surface = androidx.compose.ui.graphics.Color(0xFF151F33),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202D45)
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("POKEVALUE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(if (createAccount) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Sign in to keep your Pokémon collection private and synced across devices.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!api.isConfigured()) {
                            Text(
                                "Supabase connection details are not configured yet. The project URL and publishable key must be added to local Gradle properties before sign-in can work.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email address") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        Button(
                            onClick = {
                                if (email.isBlank() || password.length < 6) {
                                    onMessage("Enter a valid email and a password of at least 6 characters.")
                                } else if (!api.isConfigured()) {
                                    onMessage("Supabase URL and publishable key are still missing.")
                                } else {
                                    busy = true
                                    onMessage("")
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                if (createAccount) api.signUp(email, password) else api.signIn(email, password)
                                            }
                                            if (result.session != null) onSignedIn(result.session)
                                            else onMessage(result.message)
                                        } catch (e: Exception) {
                                            onMessage(e.message ?: "Authentication failed. Please try again.")
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = !busy && api.isConfigured()
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(if (createAccount) "Create account" else "Sign in")
                        }
                        TextButton(
                            onClick = { createAccount = !createAccount; onMessage("") },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(if (createAccount) "Already have an account? Sign in" else "New to PokeValue? Create an account")
                        }
                    }
                }
            }
        }
    }
}
