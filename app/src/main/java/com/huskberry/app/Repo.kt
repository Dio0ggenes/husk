package com.huskberry.app

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

data class Player(
    val id: String = "",
    val name: String = "",
    val photo: String = "",
    val role: String = "",
    val team: Int = 0,
    val zoneLat: Double? = null,
    val zoneLng: Double? = null,
    val zoneR: Double? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val updatedAt: Long = 0
)

data class Game(
    val state: String = "lobby", // lobby -> zone -> playing
    val hostId: String = "",
    val seekers: Int = 3,
    val startAt: Long = 0,
    val zoneLat: Double = 0.0,
    val zoneLng: Double = 0.0,
    val zoneR: Double = 1000.0
)

object Repo {
    private fun games() = FirebaseFirestore.getInstance().collection("games")
    private fun players(code: String) = games().document(code).collection("players")

    fun newCode(): String {
        val c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..5).map { c.random() }.joinToString("")
    }

    fun createGame(code: String, me: Player, done: (Boolean) -> Unit) {
        games().document(code).set(Game(hostId = me.id))
            .continueWithTask { players(code).document(me.id).set(me) }
            .addOnCompleteListener { done(it.isSuccessful) }
    }

    fun join(code: String, me: Player, done: (String) -> Unit) {
        games().document(code).get().addOnSuccessListener { d ->
            val g = d.toObject(Game::class.java)
            when {
                g == null -> done("No existe esa partida")
                g.state != "lobby" -> done("La partida ya ha empezado")
                else -> players(code).document(me.id).set(me)
                    .addOnCompleteListener { done(if (it.isSuccessful) "" else "Error al unirse") }
            }
        }.addOnFailureListener { done("Sin conexión o Firebase mal configurado") }
    }

    fun listenGame(code: String, cb: (Game) -> Unit): ListenerRegistration =
        games().document(code).addSnapshotListener { s, _ -> s?.toObject(Game::class.java)?.let(cb) }

    fun listenPlayers(code: String, cb: (List<Player>) -> Unit): ListenerRegistration =
        players(code).addSnapshotListener { s, _ ->
            if (s != null) cb(s.documents.mapNotNull { d -> d.toObject(Player::class.java)?.copy(id = d.id) })
        }

    fun setGame(code: String, f: Map<String, Any>) { games().document(code).update(f) }

    fun setPlayer(code: String, id: String, f: Map<String, Any>) { players(code).document(id).update(f) }

    fun startGame(code: String, ps: List<Player>, seekers: Int) {
        val n = seekers.coerceIn(1, (ps.size - 2).coerceAtLeast(1))
        val zs = ps.filter { it.zoneLat != null && it.zoneLng != null && it.zoneR != null }
        val b = FirebaseFirestore.getInstance().batch()
        ps.shuffled().forEachIndexed { i, p ->
            val role = if (i < n) "seeker" else "hider"
            val team = if (i < n) 0 else (i - n) / 2 + 1
            b.update(players(code).document(p.id), mapOf("role" to role, "team" to team))
        }
        b.update(
            games().document(code),
            mapOf(
                "state" to "playing",
                "startAt" to System.currentTimeMillis(),
                "zoneLat" to zs.map { it.zoneLat!! }.average(),
                "zoneLng" to zs.map { it.zoneLng!! }.average(),
                "zoneR" to zs.map { it.zoneR!! }.average()
            )
        )
        b.commit()
    }
}
