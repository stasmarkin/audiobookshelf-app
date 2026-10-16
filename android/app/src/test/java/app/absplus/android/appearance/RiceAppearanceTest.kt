package app.absplus.android.appearance

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.R
import app.absplus.android.widget.FullArtwork
import app.absplus.android.widget.WidgetLook
import app.absplus.android.widget.WidgetPaint
import app.absplus.android.widget.WidgetRenderer
import app.absplus.android.widget.WidgetSize
import app.absplus.android.widget.WidgetTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * RICE native appearance (docs/rice-customization.md): device mapping parity with the JS mapper, the public palette
 * reader's API guards, the read-only store reader that feeds the widget, and RICE widget colors on the standard
 * layouts. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RiceAppearanceTest {
  private lateinit var ctx: Context

  @Before
  fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    ctx.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().clear().commit()
  }

  private fun rgb(a: JSONArray) = IntArray(3) { a.getInt(it) }

  /** tests/fixtures/rice-device-mapping.json, shared with tests/rice-core.test.mjs */
  private fun fixture(): JSONObject {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "tests/fixtures/rice-device-mapping.json").exists()) dir = dir.parentFile
    return JSONObject(File(dir, "tests/fixtures/rice-device-mapping.json").readText())
  }

  @Test
  fun deviceMappingMatchesTheJsMapperExactly() {
    val cases = fixture().getJSONArray("cases")
    assertEquals(12, cases.length())
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val tonesJson = c.getJSONObject("tones")
      val tones = tonesJson.keys().asSequence().associateWith { group -> tonesJson.getJSONObject(group).let { t -> t.keys().asSequence().associateWith { tone -> rgb(t.getJSONArray(tone)) } } }
      val palette = DevicePaletteMapper.widgetPalette(tones, c.getBoolean("night"), c.getBoolean("highContrast"))!!
      val e = c.getJSONObject("expected")
      val label = "${c.getString("name")} night=${c.getBoolean("night")} hc=${c.getBoolean("highContrast")}"
      assertArrayEquals("$label background", rgb(e.getJSONArray("background")), palette.background)
      assertArrayEquals("$label surface", rgb(e.getJSONArray("surface")), palette.surface)
      assertArrayEquals("$label text", rgb(e.getJSONArray("text")), palette.text)
      assertArrayEquals("$label muted", rgb(e.getJSONArray("muted")), palette.muted)
      assertArrayEquals("$label icon", rgb(e.getJSONArray("icon")), palette.icon)
      assertArrayEquals("$label track", rgb(e.getJSONArray("track")), palette.track)
      assertArrayEquals("$label played", rgb(e.getJSONArray("played")), palette.played)
    }
    assertNull("no platform palette, no mapping", DevicePaletteMapper.widgetPalette(null, true))
    assertNull("missing tones are unavailable, never black", DevicePaletteMapper.widgetPalette(mapOf("neutral1" to emptyMap()), true))
  }

  @Test
  fun contrastMathMatchesWcag() {
    assertEquals(21.0, RiceColor.contrast(RiceColor.WHITE, RiceColor.BLACK), 1e-12)
    val repaired = RiceColor.repair(intArrayOf(88, 91, 112), listOf(RiceColor.Constraint(intArrayOf(30, 30, 46), 4.5)))
    assertTrue(RiceColor.contrast(repaired, intArrayOf(30, 30, 46)) >= 4.5)
  }

  @Test
  @Config(sdk = [30])
  fun android11HasNoPlatformPalette() {
    val device = DevicePaletteReader.read(ctx)
    assertNull(device.tones)
    assertEquals(false, device.paletteAvailable)
    assertNull(device.contrast)
    assertEquals("none", device.paletteRevision())
  }

  @Test
  @Config(sdk = [31], qualifiers = "night")
  fun android12ReadsAllPublicTonesAndNightMode() {
    val device = DevicePaletteReader.read(ctx)
    assertTrue(device.night)
    val tones = device.tones!!
    assertEquals(DeviceAppearance.GROUPS.toSet(), tones.keys)
    for (group in DeviceAppearance.GROUPS) assertEquals(DeviceAppearance.TONES, tones.getValue(group).keys.toList())
    assertNull("contrast is Android 14+", device.contrast)
  }

  @Test
  @Config(sdk = [34], qualifiers = "notnight")
  fun android14ReportsAContrastLevel() {
    val device = DevicePaletteReader.read(ctx)
    assertEquals(false, device.night)
    assertNotNull(device.contrast)
    assertTrue(device.contrast!! in -1.0..1.0)
  }

  // --- Store reader (envelopes written the way theme/rice/store.js writes them) ---

  private fun envelope(riceEnabled: Boolean, selection: JSONObject, widget: Any?, revision: Long = 3, schemaVersion: Int = 1, lastBuiltin: String = "black") =
    JSONObject()
      .put("format", "absplus-rice-store").put("schemaVersion", schemaVersion).put("revision", revision).put("riceEnabled", riceEnabled)
      .put("lastBuiltin", lastBuiltin).put("selection", selection).put("rememberedRiceSelection", JSONObject.NULL).put("presets", JSONArray())
      .put("widget", widget ?: JSONObject.NULL).toString()

  private fun colors() = JSONObject().apply { for ((i, role) in listOf("background", "surface", "text", "muted", "icon", "track", "played").withIndex()) put(role, JSONArray(listOf(i, i + 10, i + 20))) }

  private fun widget(kind: String = "preset", family: String = "standard", mode: Any = JSONObject.NULL, revision: Long = 3) =
    JSONObject().put("revision", revision).put("selectionKind", kind).put("deviceMode", mode).put("family", family).put("colors", colors())

  private fun save(text: String, slot: String = "A", digest: String = AppearanceSnapshotReader.sha256(text), revision: Long = 3) {
    val head = JSONObject().put("current", JSONObject().put("slot", slot).put("revision", revision).put("sha256", digest)).put("previous", JSONObject.NULL)
    ctx.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().putString("rice.v1.slot$slot", text).putString("rice.v1.head", head.toString()).putString("theme", "llama").commit()
  }

  @Test
  fun committedRicePaletteIsReadOnlyThroughAVerifiedHead() {
    save(envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), widget()))
    val committed = AppearanceSnapshotReader.read(ctx) as AppearanceSnapshotReader.Committed.Rice
    assertEquals("standard", committed.family)
    assertArrayEquals(intArrayOf(0, 10, 20), committed.palette.background)
    assertArrayEquals(intArrayOf(6, 16, 26), committed.palette.played)
    assertNull(committed.deviceMode)
  }

  @Test
  fun anythingUnverifiableFallsBackToBuiltIns() {
    val text = envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), widget())
    // Wrong digest: the legacy key (llama) decides
    save(text, digest = "0".repeat(64))
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("llama"), AppearanceSnapshotReader.read(ctx))
    // Newer schema: not trusted
    save(envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), widget(), schemaVersion = 2))
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("llama"), AppearanceSnapshotReader.read(ctx))
    // Projection revision doesn't match the head: envelope's built-in
    save(envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), widget(revision = 2)))
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("black"), AppearanceSnapshotReader.read(ctx))
    // Out-of-range channel
    val bad = widget().also { it.getJSONObject("colors").put("text", JSONArray(listOf(1, 2, 300))) }
    save(envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), bad))
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("black"), AppearanceSnapshotReader.read(ctx))
    // Garbage head
    ctx.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().putString("rice.v1.head", "{").commit()
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("llama"), AppearanceSnapshotReader.read(ctx))
  }

  @Test
  fun theEnvelopeSelectionIsAuthoritativeOverTheLegacyKeyOnceRiceWasUsed() {
    save(envelope(false, JSONObject().put("kind", "builtin").put("id", "light"), null))
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("light"), AppearanceSnapshotReader.read(ctx))
    assertEquals(WidgetTheme.STANDARD, WidgetTheme.current(ctx))
  }

  @Test
  fun eInkIsAValidBuiltInBeforeAndAfterRiceWasUsedAndKeepsTheStandardWidget() {
    ctx.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().putString("theme", "eink").commit()
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("eink"), AppearanceSnapshotReader.read(ctx))
    save(envelope(false, JSONObject().put("kind", "builtin").put("id", "eink"), null, lastBuiltin = "eink"))
    assertEquals(AppearanceSnapshotReader.Committed.Builtin("eink"), AppearanceSnapshotReader.read(ctx))
    assertEquals(WidgetTheme.STANDARD, WidgetTheme.current(ctx))
  }

  @Test
  fun llamaFamilyRiceCopiesKeepTheLlamaWidget() {
    save(envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), widget(family = "llama")))
    assertEquals(WidgetLook(WidgetTheme.LLAMA, null), WidgetTheme.look(ctx))
  }

  @Test
  @Config(sdk = [31], qualifiers = "night")
  fun deviceModeReResolvesTheCurrentDevicePaletteForEachWidgetUpdate() {
    save(envelope(true, JSONObject().put("kind", "device").put("mode", "follow"), widget(kind = "device", mode = "follow")))
    val look = WidgetTheme.look(ctx)
    assertEquals(WidgetTheme.STANDARD, look.theme)
    val device = DevicePaletteReader.read(ctx)
    assertArrayEquals("not the stale stored colors", DevicePaletteMapper.widgetPalette(device.tones, true)!!.background, look.palette!!.background)
  }

  @Test
  @Config(sdk = [30])
  fun android11KeepsTheStandardWidgetForRicePresets() {
    save(envelope(true, JSONObject().put("kind", "preset").put("id", "p-" + "0".repeat(24)), widget()))
    assertEquals(WidgetLook(WidgetTheme.STANDARD, null), WidgetTheme.look(ctx))
  }

  // --- Widget paint on the real standard layouts ---

  @Test
  fun ricePaletteRecolorsEveryStandardLayoutWithoutChangingIdsOrActions() {
    val palette = WidgetPalette(intArrayOf(23, 33, 38), intArrayOf(17, 59, 53), intArrayOf(245, 233, 200), intArrayOf(178, 200, 188), intArrayOf(245, 233, 200), intArrayOf(74, 121, 110), intArrayOf(254, 187, 162))
    val cases = listOf(WidgetSize.COMPACT to FullArtwork.Presentation.NORMAL, WidgetSize.WIDE to FullArtwork.Presentation.NORMAL) + FullArtwork.Presentation.values().map { WidgetSize.FULL to it }
    for ((size, presentation) in cases) {
      val layout = WidgetRenderer.layoutFor(size, WidgetTheme.STANDARD, presentation)
      val views = RemoteViews(ctx.packageName, layout)
      WidgetPaint.apply(views, size, WidgetLook(WidgetTheme.STANDARD, palette))
      val root = LayoutInflater.from(ctx).inflate(layout, FrameLayout(ctx), false)
      views.reapply(ctx, root) // throws if a method isn't remotable or a view is missing
      val label = "$size $presentation"
      assertEquals(label, RiceColor.toArgb(palette.text), root.findViewById<TextView>(R.id.widgetMediaTitle).currentTextColor)
      assertEquals(label, RiceColor.toArgb(palette.muted), root.findViewById<TextView>(R.id.widgetArtistText).currentTextColor)
      assertEquals(label, RiceColor.toArgb(palette.background), root.findViewById<android.view.View>(R.id.widgetBackground).backgroundTintList!!.defaultColor)
      assertNotNull(label, root.findViewById<ImageView>(R.id.widgetPlayPauseButton).colorFilter)
      if (size == WidgetSize.FULL) assertEquals(label, RiceColor.toArgb(palette.played), root.findViewById<ProgressBar>(R.id.widgetProgress).progressTintList!!.defaultColor)
    }
  }

  @Test
  fun builtInLooksAreNotTouched() {
    val views = RemoteViews(ctx.packageName, R.layout.media_player_widget)
    WidgetPaint.apply(views, WidgetSize.COMPACT, WidgetLook(WidgetTheme.STANDARD, null))
    WidgetPaint.apply(views, WidgetSize.COMPACT, WidgetLook(WidgetTheme.LLAMA, WidgetPalette(IntArray(3), IntArray(3), IntArray(3), IntArray(3), IntArray(3), IntArray(3), IntArray(3))))
    val root = LayoutInflater.from(ctx).inflate(R.layout.media_player_widget, FrameLayout(ctx), false)
    val before = root.findViewById<TextView>(R.id.widgetMediaTitle).currentTextColor
    views.reapply(ctx, root)
    assertEquals(before, root.findViewById<TextView>(R.id.widgetMediaTitle).currentTextColor)
    assertNull(root.findViewById<android.view.View>(R.id.widgetBackground).backgroundTintList)
    assertNull(root.findViewById<ImageView>(R.id.widgetPlayPauseButton).colorFilter)
  }

  @Test
  fun lightPalettesGetDarkInkButtonFacesAndDarkPalettesKeepTheStandardFace() {
    // S22 Follow device (light): the standard white-wash faces vanished on a light container
    fun faces(background: IntArray): List<Int> {
      val palette = WidgetPalette(background, background, intArrayOf(26, 27, 32), intArrayOf(68, 70, 79), intArrayOf(26, 27, 32), intArrayOf(224, 221, 225), intArrayOf(71, 93, 146))
      val views = RemoteViews(ctx.packageName, R.layout.media_player_widget_full)
      WidgetPaint.apply(views, WidgetSize.FULL, WidgetLook(WidgetTheme.STANDARD, palette))
      val root = LayoutInflater.from(ctx).inflate(R.layout.media_player_widget_full, FrameLayout(ctx), false)
      views.reapply(ctx, root)
      return listOf(R.id.widgetRewindButton, R.id.widgetPlayPauseButton, R.id.widgetFastForwardButton).map {
        (root.findViewById<ImageView>(it).background as GradientDrawable).color!!.defaultColor
      }
    }
    assertEquals(List(3) { 0x1A000000 }, faces(intArrayOf(254, 251, 255)))
    assertEquals(List(3) { 0x1AFFFFFF }, faces(intArrayOf(26, 27, 32)))
  }
}
