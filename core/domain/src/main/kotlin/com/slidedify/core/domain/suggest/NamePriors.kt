package com.slidedify.core.domain.suggest

/** What a playlist's name says about its style: "Gym" means intense, "Relax" means calm. */
data class NamePrior(val energy: Float?, val tags: Set<String>, val keyword: String)

object NamePriors {
    private data class Entry(val energy: Float?, val tags: Set<String>)

    private fun e(energy: Float?, vararg tags: String) = Entry(energy, tags.toSet())

    /** Spanish and English words people use in playlist names. Keys are normalized (no accents). */
    private val dictionary: Map<String, Entry> = mapOf(
        // Intense
        "gym" to e(0.92f, "workout", "energetic"), "workout" to e(0.92f, "workout", "energetic"),
        "entreno" to e(0.92f, "workout", "energetic"), "entrenamiento" to e(0.92f, "workout", "energetic"),
        "training" to e(0.9f, "workout", "energetic"), "cardio" to e(0.92f, "workout", "energetic"),
        "running" to e(0.9f, "running", "energetic"), "correr" to e(0.9f, "running", "energetic"),
        "run" to e(0.88f, "running", "energetic"), "pesas" to e(0.92f, "workout"), "crossfit" to e(0.95f, "workout"),
        "punchy" to e(0.9f, "energetic", "power"), "power" to e(0.88f, "power", "energetic"),
        "hype" to e(0.9f, "hype", "energetic"), "pump" to e(0.9f, "workout"), "beast" to e(0.92f, "aggressive"),
        "energia" to e(0.88f, "energetic"), "energy" to e(0.88f, "energetic"), "fuerza" to e(0.85f, "power"),
        "fiesta" to e(0.85f, "party", "dance"), "party" to e(0.85f, "party", "dance"), "previa" to e(0.85f, "party"),
        "perreo" to e(0.85f, "reggaeton", "party"), "boliche" to e(0.85f, "party", "dance"), "dance" to e(0.82f, "dance"),
        "baile" to e(0.82f, "dance"), "bailar" to e(0.82f, "dance"), "club" to e(0.85f, "dance", "house"),
        "rave" to e(0.92f, "techno", "edm"), "metal" to e(0.95f, "metal"), "rock" to e(0.72f, "rock"),
        "punk" to e(0.9f, "punk"), "trap" to e(0.75f, "trap"), "reggaeton" to e(0.75f, "reggaeton"),
        "techno" to e(0.85f, "techno"), "house" to e(0.75f, "house"), "edm" to e(0.9f, "edm"),
        "electro" to e(0.8f, "electro"), "electronica" to e(0.75f, "electro"), "cumbia" to e(0.7f, "cumbia"),
        "rap" to e(0.7f, "rap", "hip-hop"), "hiphop" to e(0.65f, "hip-hop"), "hip-hop" to e(0.65f, "hip-hop"),
        "manija" to e(0.88f, "energetic"), "motivacion" to e(0.85f, "energetic"), "motivation" to e(0.85f, "energetic"),
        // Calm
        "relax" to e(0.12f, "chill", "relax"), "relajarse" to e(0.12f, "relax"), "relajante" to e(0.12f, "relax"),
        "chill" to e(0.2f, "chill"), "chilling" to e(0.2f, "chill"), "calma" to e(0.12f, "calm"),
        "calm" to e(0.12f, "calm"), "tranqui" to e(0.18f, "chill"), "tranquila" to e(0.15f, "calm"),
        "tranquilo" to e(0.15f, "calm"), "dormir" to e(0.05f, "sleep", "ambient"), "sleep" to e(0.05f, "sleep", "ambient"),
        "siesta" to e(0.08f, "sleep"), "estudio" to e(0.15f, "study", "focus"), "estudiar" to e(0.15f, "study", "focus"),
        "study" to e(0.15f, "study", "focus"), "focus" to e(0.15f, "focus"), "concentracion" to e(0.15f, "focus"),
        "lofi" to e(0.2f, "lo-fi"), "lo-fi" to e(0.2f, "lo-fi"), "acustico" to e(0.3f, "acoustic"),
        "acoustic" to e(0.3f, "acoustic"), "piano" to e(0.2f, "piano"), "jazz" to e(0.35f, "jazz"),
        "lluvia" to e(0.2f, "chill", "mellow"), "rain" to e(0.2f, "chill", "mellow"), "domingo" to e(0.25f, "chill"),
        "sunday" to e(0.25f, "chill"), "cafe" to e(0.3f, "acoustic", "jazz"), "coffee" to e(0.3f, "acoustic", "jazz"),
        "yoga" to e(0.08f, "meditation", "ambient"), "meditacion" to e(0.05f, "meditation"), "ambient" to e(0.1f, "ambient"),
        "triste" to e(0.22f, "sad"), "sad" to e(0.22f, "sad"), "bajon" to e(0.2f, "sad"), "melancolia" to e(0.22f, "melancholic"),
        "mellow" to e(0.2f, "mellow"), "suave" to e(0.2f, "soft"), "soft" to e(0.2f, "soft"), "noche" to e(0.3f, "chill"),
        "night" to e(0.3f, "chill"), "atardecer" to e(0.3f, "chill"), "sunset" to e(0.3f, "chill"),
        "romantica" to e(0.3f, "romantic", "love"), "romantic" to e(0.3f, "romantic", "love"), "amor" to e(0.35f, "love"),
        "love" to e(0.35f, "love"), "baladas" to e(0.25f, "ballad"), "balada" to e(0.25f, "ballad"), "clasica" to e(0.25f, "classical"),
        "classical" to e(0.25f, "classical"), "folk" to e(0.35f, "folk"), "soul" to e(0.4f, "soul"), "bossa" to e(0.25f, "bossa nova"),
        // Middle ground / no clear energy
        "viaje" to e(0.55f), "roadtrip" to e(0.6f), "road" to e(0.6f), "auto" to e(0.6f), "pop" to e(0.6f, "pop"),
        "indie" to e(0.5f, "indie"), "trabajo" to e(0.35f, "focus"), "work" to e(0.35f, "focus"),
    )

    /** The strongest hint in [name], or null if the name says nothing about style. */
    fun of(name: String): NamePrior? {
        val normalized = normalizeText(name)
        val words = normalized.split(' ', '_', '/').filter { it.isNotBlank() }
        val hits = buildList {
            // Whole words first, then compounds like "gymtime" or "chillvibes".
            words.forEach { word -> dictionary[word]?.let { add(word to it) } }
            if (isEmpty()) {
                dictionary.entries
                    .filter { (key, _) -> key.length >= 4 && normalized.contains(key) }
                    .forEach { add(it.key to it.value) }
            }
        }
        if (hits.isEmpty()) return null
        val energies = hits.mapNotNull { it.second.energy }
        return NamePrior(
            energy = if (energies.isEmpty()) null else energies.average().toFloat(),
            tags = hits.flatMapTo(mutableSetOf()) { it.second.tags },
            keyword = hits.first().first,
        )
    }
}
