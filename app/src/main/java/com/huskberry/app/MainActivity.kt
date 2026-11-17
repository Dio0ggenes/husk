package com.huskberry.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hb", Context.MODE_PRIVATE) }
    val myId = remember {
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var photo by remember { mutableStateOf(prefs.getString("photo", "") ?: "") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var game by remember { mutableStateOf(Game()) }
    var players by remember { mutableStateOf(listOf<Player>()) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    DisposableEffect(code) {
        if (code.isEmpty()) return@DisposableEffect onDispose { }
        val a = Repo.listenGame(code) { game = it }
        val b = Repo.listenPlayers(code) { players = it }
        onDispose { a.remove(); b.remove() }
    }

    fun me() = Player(id = myId, name = name.trim(), photo = photo)

    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            code.isEmpty() -> Home(
                name, photo, error,
                onName = { name = it; prefs.edit().putString("name", it).apply() },
                onPhoto = { photo = it; prefs.edit().putString("photo", it).apply() },
                onCreate = {
                    if (name.isBlank()) error = "Pon un nombre primero" else {
                        error = ""
                        val c = Repo.newCode()
                        Repo.createGame(c, me()) { ok -> if (ok) code = c else error = "No se pudo crear la partida (¿Firebase configurado?)" }
                    }
                },
                onJoin = { raw ->
                    val c = raw.trim().uppercase()
                    if (name.isBlank()) error = "Pon un nombre primero" else {
                        error = ""
                        Repo.join(c, me()) { res -> if (res.isEmpty()) code = c else error = res }
                    }
                }
            )
            game.state == "lobby" -> Lobby(
                code, game, players, game.hostId == myId,
                onSeekers = { Repo.setGame(code, mapOf("seekers" to it)) },
                onNext = { Repo.setGame(code, mapOf("state" to "zone")) },
                onLeave = { code = ""; game = Game(); players = listOf() }
            )
            game.state == "zone" -> ZoneScreen(
                players, granted, game.hostId == myId,
                onSave = { c, r ->
                    Repo.setPlayer(code, myId, mapOf("zoneLat" to c.latitude, "zoneLng" to c.longitude, "zoneR" to r))
                },
                onStart = { Repo.startGame(code, players, game.seekers) }
            )
            else -> GameScreen(game, players, myId, code, granted)
        }
    }
}
