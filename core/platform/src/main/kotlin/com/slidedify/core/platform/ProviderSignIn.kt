package com.slidedify.core.platform

import android.content.Context
import com.slidedify.core.model.ProviderId

/**
 * Android-side sign-in entry point for a provider. Starting a login needs a Context (browser,
 * SDK activity...), so it cannot live in the pure domain. Each `:provider:*` module that needs
 * a login contributes one of these to a Hilt set; features look it up by [providerId].
 */
interface ProviderSignIn {
    val providerId: ProviderId

    fun start(context: Context)
}
