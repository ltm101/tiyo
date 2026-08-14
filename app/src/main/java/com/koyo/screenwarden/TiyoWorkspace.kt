package com.koyo.screenwarden

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** Shared workspace rules used by the native Agent and Tiyo's file studio. */
object TiyoWorkspace {

    fun root(context: Context): File {
        val canUseSharedStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        return if (canUseSharedStorage) {
            File(Environment.getExternalStorageDirectory(), "Tiyo")
        } else {
            File(context.getExternalFilesDir(null), "workspace")
        }.apply { mkdirs() }
    }

    fun phoneRoot(): File = Environment.getExternalStorageDirectory()

    fun inbox(context: Context): File = File(root(context), "inbox").apply { mkdirs() }

    fun projects(context: Context): File = File(root(context), "projects").apply { mkdirs() }

    /** 学习模式资料目录：/sdcard/Tiyo/study */
    fun study(context: Context): File = File(root(context), "study").apply { mkdirs() }

    fun isSharedStorageAvailable(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}
