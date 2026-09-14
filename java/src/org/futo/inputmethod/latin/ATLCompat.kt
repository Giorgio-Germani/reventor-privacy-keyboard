package org.futo.inputmethod.latin

import android.content.Context
import android.content.ContextWrapper
import android.inputmethodservice.InputMethodService
import android.os.Build
import org.futo.inputmethod.latin.BuildConfig
import java.lang.reflect.Method

object ATLCompat {
    /** Any uses of this to determine logic is a bug that should be fixed in ATL */
    val IsATL = BuildConfig.DEBUG && Build.PRODUCT == "atl"

    fun launchKeyboard(context: Context, layershell: Boolean) {
        if(!IsATL) return

        val ime = LatinIME()

        try {
            val baseField = ContextWrapper::class.java.getDeclaredField("baseContext")
            baseField.isAccessible = true
            baseField.set(ime, context)
        } catch (e: Exception) {
            e.printStackTrace()
            println("Setting mBase failed: $e")
        }

        try {
            val launchMethod: Method? =
                ime.javaClass.asSubclass(InputMethodService::class.java)
                    .getMethod("launch_keyboard", Boolean::class.javaPrimitiveType)

            if(launchMethod != null) {
                launchMethod.invoke(ime, layershell)
                println("launch_keyboard invoked $launchMethod")
            } else {
                println("launch_keyboard method not found")
            }

            ime.uixManager
        } catch(e: Exception) {
            e.printStackTrace()
            println("Launching failed: $e")
        }
    }
}