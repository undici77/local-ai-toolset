package com.undici77.aitoolset.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** The only place that touches PasswordSafe. Call off the EDT, and only from a user-started action. */
object Secrets {
    private fun attrs(providerId: String) = CredentialAttributes(generateServiceName("Local AI Toolset", providerId))

    fun get(providerId: String): String? = PasswordSafe.instance.getPassword(attrs(providerId))

    fun set(providerId: String, key: String?) {
        PasswordSafe.instance.setPassword(attrs(providerId), key?.takeIf { it.isNotBlank() })
    }
}
