package app.absplus.android.updater

import android.content.Context

/** Fixed contributor channel: no fixture sources or production-package installs. */
object UpdaterConfig {
  const val TEST_SOURCES_SUPPORTED = false

  fun source(context: Context): UpdateSource = UpdateSource.Production.copy(
    targetPackage = context.packageName,
    trustedSignerSha256 = setOf("25802304ac05945342c5266666d43933dd93313836553c1e064f26782a5490a7"),
    downloadsEnabled = false
  )

  fun setTestSource(context: Context, json: String): String =
    "Test update sources are not available in contributor builds"

  fun clearTestSource(context: Context) {}
}
