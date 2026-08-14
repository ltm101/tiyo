package com.koyo.screenwarden

/**
 * Non-secret protocol and package defaults.
 * User credentials belong in runtime settings backed by [TiyoSecureStore].
 */
object Config {
    const val IMAP_HOST = "imap.qq.com"
    const val IMAP_PORT = "993"
    const val SMTP_HOST = "smtp.qq.com"
    const val SMTP_PORT = "587"

    val FORWARD_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    val REPLY_TARGET_PKGS = mapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ"
    )
}
