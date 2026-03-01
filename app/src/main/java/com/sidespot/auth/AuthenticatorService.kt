package com.sidespot.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/** Stub authenticator required by AccountManager. All methods return unsupported. */
class AuthenticatorService : Service() {

    private lateinit var authenticator: StubAuthenticator

    override fun onCreate() {
        authenticator = StubAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    private class StubAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(r: AccountAuthenticatorResponse?, s: String?) = throw UnsupportedOperationException()
        override fun addAccount(r: AccountAuthenticatorResponse?, s: String?, s2: String?, ss: Array<out String>?, b: Bundle?) = null
        override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, b: Bundle?) = null
        override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, s: String?, b: Bundle?) = null
        override fun getAuthTokenLabel(s: String?) = null
        override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, s: String?, b: Bundle?) = null
        override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, ss: Array<out String>?) = null
    }
}
