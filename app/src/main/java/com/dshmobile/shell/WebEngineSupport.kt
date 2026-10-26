package com.dshmobile.shell

/** OEM WebView app versions need not use Chromium's version numbering. */
internal object WebEngineSupport {
    fun chromiumMajor(userAgent: String): Int? =
        Regex("\\b(?:Chrome|Chromium)/(\\d+)").find(userAgent)?.groupValues?.get(1)?.toIntOrNull()
}
