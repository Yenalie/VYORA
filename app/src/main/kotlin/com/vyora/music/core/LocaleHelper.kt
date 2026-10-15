package com.vyora.music.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** Per-app language without AppCompat: wraps the Activity base context with the chosen locale. */
object LocaleHelper {
    fun wrap(base: Context, tag: String): Context {
        if (tag.isBlank()) {
            Locale.setDefault(android.content.res.Resources.getSystem().configuration.locales[0])
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
