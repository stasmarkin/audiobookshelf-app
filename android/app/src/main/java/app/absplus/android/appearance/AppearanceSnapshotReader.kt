package app.absplus.android.appearance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Reads the committed RICE appearance for native surfaces that run without the WebView (the home-screen widget).
 * Read-only: the app's PresetStore (theme/rice/store.js) is the only writer.
 *
 * It follows the head pointer to the current slot, checks size, SHA-256, format, schema and revision, and reads only
 * the small widget projection. Anything unreadable, unverifiable or from a newer schema gives the built-in result, so
 * a damaged store can only ever mean "the standard look". Before RICE was first used, the legacy `theme` key decides.
 */
object AppearanceSnapshotReader {
  private const val PREFS = "CapacitorStorage"
  private const val KEY_HEAD = "rice.v1.head"
  private const val KEY_LEGACY = "theme"
  private const val MAX_SLOT_CHARS = 1024 * 1024
  // Every selectable built-in (theme/rice/schema.js BUILTIN_IDS), not only the custom-snapshot bases
  private val BUILTIN_IDS = setOf("dark", "black", "light", "llama", "eink")
  private val ROLES = listOf("background", "surface", "text", "muted", "icon", "track", "played")

  sealed interface Committed {
    /** A built-in theme id (dark, black, light, llama, eink), or null when nothing valid is saved */
    data class Builtin(val themeId: String?) : Committed

    data class Rice(val family: String, val palette: WidgetPalette, val deviceMode: String?) : Committed
  }

  fun read(context: Context): Committed {
    val prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return Committed.Builtin(null)
    val legacy = runCatching { prefs.getString(KEY_LEGACY, null) }.getOrNull()?.takeIf { it in BUILTIN_IDS }
    val headText = runCatching { prefs.getString(KEY_HEAD, null) }.getOrNull() ?: return Committed.Builtin(legacy)
    return runCatching { fromHead(headText) { key -> prefs.getString(key, null) } }.getOrNull() ?: Committed.Builtin(legacy)
  }

  /** Pure part, for tests: head JSON + slot lookup -> committed appearance, or null when not verifiable. */
  fun fromHead(headText: String, slot: (String) -> String?): Committed? {
    if (headText.length > 1024) return null
    val current = JSONObject(headText).getJSONObject("current")
    val slotName = current.getString("slot")
    if (slotName != "A" && slotName != "B") return null
    val revision = current.getLong("revision")
    val digest = current.getString("sha256")
    val text = slot("rice.v1.slot$slotName") ?: return null
    if (text.length > MAX_SLOT_CHARS || sha256(text) != digest) return null
    val envelope = JSONObject(text)
    if (envelope.optString("format") != "absplus-rice-store" || envelope.optInt("schemaVersion") != 1 || envelope.optLong("revision") != revision) return null
    val lastBuiltin = envelope.optString("lastBuiltin").takeIf { it in BUILTIN_IDS } ?: return null
    val selection = envelope.getJSONObject("selection")
    val riceEnabled = envelope.optBoolean("riceEnabled", false)
    val widget = envelope.opt("widget")
    if (!riceEnabled || widget !is JSONObject) {
      val id = if (selection.optString("kind") == "builtin") selection.optString("id").takeIf { it in BUILTIN_IDS } else null
      return Committed.Builtin(id ?: lastBuiltin)
    }
    return projection(widget, revision) ?: Committed.Builtin(lastBuiltin)
  }

  private fun projection(widget: JSONObject, revision: Long): Committed.Rice? {
    if (widget.optLong("revision") != revision) return null
    val kind = widget.optString("selectionKind")
    if (kind !in setOf("starter", "preset", "device")) return null
    val family = widget.optString("family").takeIf { it == "standard" || it == "llama" } ?: return null
    val deviceMode = if (kind == "device") widget.optString("deviceMode").takeIf { it == "follow" || it == "dark" } ?: return null else null
    val colors = widget.getJSONObject("colors")
    val values = ROLES.map { role -> rgb(colors.optJSONArray(role)) ?: return null }
    return Committed.Rice(family, WidgetPalette(values[0], values[1], values[2], values[3], values[4], values[5], values[6]), deviceMode)
  }

  private fun rgb(array: JSONArray?): IntArray? {
    if (array == null || array.length() != 3) return null
    return IntArray(3) { i ->
      val v = array.opt(i)
      if (v !is Int || v !in 0..255) return null
      v
    }
  }

  fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
