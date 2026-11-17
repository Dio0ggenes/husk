package com.huskberry.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import kotlin.math.ln

// ---------- utilidades ----------

fun decodePhoto(b64: String): ImageBitmap? = try {
    val b = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
} catch (e: Exception) { null }

fun encodePhoto(ctx: Context, uri: Uri): String = try {
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    val bmp = ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    if (bmp == null) "" else {
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, 96, 96, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
} catch (e: Exception) { "" }

@SuppressLint("MissingPermission")
suspend fun getLoc(ctx: Context): Location? = try {
    LocationServices.getFusedLocationProviderClient(ctx)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
} catch (e: Exception) { null }

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun Avatar(photo: String, name: String, size: Dp = 48.dp) {
    val bmp = remember(photo) { decodePhoto(photo) }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF6A4C93)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White)
        }
    }
}

// ---------- pantallas ----------

@Composable
fun Home(
    name: String, photo: String, error: String,
    onName: (String) -> Unit, onPhoto: (String) -> Unit,
    onCreate: () -> Unit, onJoin: (String) -> Unit
) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onPhoto(encodePhoto(ctx, u))
    }
    var codeIn by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        Text("Huskberry", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.clickable { picker.launch("image/*") }) { Avatar(photo, name.ifBlank { "?" }, 96.dp) }
        Text("Toca el círculo para elegir tu foto")
        OutlinedTextField(name, onName, label = { Text("Tu nombre") }, singleLine = true)
        Button(onClick = onCreate) { Text("Crear partida") }
        OutlinedTextField(codeIn, { codeIn = it.uppercase() }, label = { Text("Código de partida") }, singleLine = true)
        Button(onClick = { onJoin(codeIn) }, enabled = codeIn.length >= 4) { Text("Unirse") }
        if (error.isNotEmpty()) Text(error, color = Color.Red)
    }
}

@Composable
fun Lobby(
    code: String, game: Game, players: List<Player>, isHost: Boolean,
    onSeekers: (Int) -> Unit, onNext: () -> Unit, onLeave: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sala: $code", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Comparte este código con los demás jugadores (${players.size} en la sala)")
        LazyColumn(Modifier.weight(1f)) {
            items(players) { p ->
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(p.photo, p.name)
                    Text(p.name + if (p.id == game.hostId) " (anfitrión)" else "")
                }
            }
        }
        if (isHost) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Buscadores: ${game.seekers}")
                OutlinedButton(onClick = { if (game.seekers > 1) onSeekers(game.seekers - 1) }) { Text("−") }
                OutlinedButton(onClick = { onSeekers(game.seekers + 1) }) { Text("+") }
            }
            Button(onClick = onNext, enabled = players.size >= 3, modifier = Modifier.fillMaxWidth()) {
                Text(if (players.size >= 3) "Elegir zona de juego" else "Hacen falta al menos 3 jugadores")
            }
        } else {
            Text("Esperando a que el anfitrión inicie la partida…")
        }
        TextButton(onClick = onLeave) { Text("Salir") }
    }
}

@Composable
fun ZoneScreen(
    players: List<Player>, granted: Boolean, isHost: Boolean,
    onSave: (LatLng, Double) -> Unit, onStart: () -> Unit
) {
    val ctx = LocalContext.current
    val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(40.0, -3.7), 5f) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var radius by remember { mutableStateOf(1000f) }
    LaunchedEffect(Unit) {
        getLoc(ctx)?.let { cam.animate(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 13f)) }
    }
    val done = players.count { it.zoneLat != null }
    Column(Modifier.fillMaxSize()) {
        Text("Toca el mapa para marcar el centro de tu zona y ajusta el radio.", Modifier.padding(12.dp))
        GoogleMap(
            Modifier.weight(1f).fillMaxWidth(),
            cameraPositionState = cam,
            properties = MapProperties(isMyLocationEnabled = granted),
            onMapClick = { center = it }
        ) {
            center?.let {
                Circle(center = it, radius = radius.toDouble(), strokeColor = Color(0xFFE63946), fillColor = Color(0x33E63946), strokeWidth = 4f)
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Radio: ${radius.toInt()} m")
            Slider(value = radius, onValueChange = { radius = it }, valueRange = 200f..5000f)
            Button(onClick = { center?.let { onSave(it, radius.toDouble()) } }, enabled = center != null, modifier = Modifier.fillMaxWidth()) {
                Text("Confirmar mi zona")
            }
            Text("Zonas confirmadas: $done/${players.size}")
            if (isHost) {
                Button(onClick = onStart, enabled = done == players.size && done >= 3, modifier = Modifier.fillMaxWidth()) {
                    Text("Empezar partida (media de las zonas)")
                }
            } else Text("Esperando al anfitrión…")
        }
    }
}

@Composable
fun Compass(me: Location?, target: LatLng, title: String) {
    val ctx = LocalContext.current
    var azimuth by remember { mutableStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val r = FloatArray(9); val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                SensorManager.getOrientation(r, o)
                azimuth = Math.toDegrees(o[0].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.Bold)
        if (me == null) {
            Text("Buscando tu posición…")
        } else {
            val t = Location("t").apply { latitude = target.latitude; longitude = target.longitude }
            Text("▲", fontSize = 72.sp, color = Color(0xFFE63946), modifier = Modifier.rotate(me.bearingTo(t) - azimuth))
            Text("A ${me.distanceTo(t).toInt()} m")
        }
    }
}

@Composable
fun GameScreen(game: Game, players: List<Player>, myId: String, code: String, granted: Boolean) {
    val ctx = LocalContext.current
    val meP = players.firstOrNull { it.id == myId }
    val role = meP?.role ?: ""
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var showRules by remember { mutableStateOf(true) }
    var myLoc by remember { mutableStateOf<Location?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val releaseAt = game.startAt + 5 * 60 * 1000L
    val released = now >= releaseAt
    val seekerCount = players.count { it.role == "seeker" }

    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(role) {
        if (role == "hider") {
            while (true) {
                getLoc(ctx)?.let {
                    Repo.setPlayer(code, myId, mapOf("lat" to it.latitude, "lng" to it.longitude, "updatedAt" to System.currentTimeMillis()))
                }
                delay(120_000)
            }
        } else if (role == "seeker") {
            while (true) { myLoc = getLoc(ctx); delay(3000) }
        }
    }

    val zoom = (16 - ln(game.zoneR.coerceAtLeast(100.0) / 300.0) / ln(2.0)).toFloat().coerceIn(8f, 17f)
    val cam = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(game.zoneLat, game.zoneLng), zoom) }
    val hiderPos = players.filter { it.role == "hider" && it.lat != null && it.lng != null }
        .groupBy { it.team }.mapValues { e -> e.value.maxByOrNull { it.updatedAt }!! }

    val rules = "• Hay $seekerCount buscadores y el resto son corredores en grupos de 2.\n" +
        "• Los corredores tienen 5 minutos para alejarse y esconderse lo máximo posible.\n" +
        "• Pasados los 5 minutos, los buscadores salen (pueden usar bicis y otros medios).\n" +
        "• Cada 2 minutos, la posición de cada grupo de corredores aparece como un pincho en el mapa de los buscadores.\n" +
        "• Toca un pincho para ver el nombre del grupo y abrir una brújula hacia él.\n" +
        "• Quedaos dentro del círculo rojo de la zona de juego."
    if (showRules) {
        AlertDialog(
            onDismissRequest = { showRules = false },
            confirmButton = { TextButton(onClick = { showRules = false }) { Text("¡Entendido!") } },
            title = { Text("Reglas de Huskberry") },
            text = { Text(rules) }
        )
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            Modifier.fillMaxSize(),
            cameraPositionState = cam,
            properties = MapProperties(isMyLocationEnabled = granted),
            onMapClick = { selected = null }
        ) {
            Circle(center = LatLng(game.zoneLat, game.zoneLng), radius = game.zoneR, strokeColor = Color(0xFFE63946), fillColor = Color(0x1AE63946), strokeWidth = 4f)
            if (role == "seeker" && released) {
                hiderPos.forEach { (team, p) ->
                    val pos = LatLng(p.lat!!, p.lng!!)
                    key(team, p.updatedAt) {
                        Marker(state = MarkerState(position = pos), title = "Grupo $team", onClick = { selected = team; false })
                    }
                }
            }
        }
        Card(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
            Column(Modifier.padding(10.dp)) {
                when {
                    role == "seeker" && !released -> {
                        Text("Eres BUSCADOR", fontWeight = FontWeight.Bold)
                        Text("Salís en ${fmt(releaseAt - now)}")
                    }
                    role == "seeker" -> {
                        Text("Eres BUSCADOR", fontWeight = FontWeight.Bold)
                        Text("Toca un pincho para abrir la brújula (se actualiza cada 2 min)")
                    }
                    role == "hider" -> {
                        val mates = players.filter { it.role == "hider" && it.team == meP?.team && it.id != myId }.joinToString { it.name }
                        Text("Eres CORREDOR · Grupo ${meP?.team}" + if (mates.isNotEmpty()) " con $mates" else "", fontWeight = FontWeight.Bold)
                        Text(if (!released) "¡Corre y escóndete! Los buscadores salen en ${fmt(releaseAt - now)}" else "Los buscadores ya van a por vosotros")
                    }
                    else -> Text("Asignando roles…")
                }
                TextButton(onClick = { showRules = true }) { Text("Reglas") }
            }
        }
        val sel = selected?.let { hiderPos[it] }
        if (role == "seeker" && released && sel != null) {
            Card(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                Compass(myLoc, LatLng(sel.lat!!, sel.lng!!), "Grupo ${sel.team}")
            }
        }
    }
}
