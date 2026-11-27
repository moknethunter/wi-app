package com.mokasas.wikall

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

internal fun registerMissedPush(context: Context, onToken: (String) -> Unit) {
    if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) return
    FirebaseMessaging.getInstance().token.addOnSuccessListener(onToken)
}
