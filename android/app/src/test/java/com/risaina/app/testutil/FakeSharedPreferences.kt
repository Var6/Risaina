package com.risaina.app.testutil

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk

/** In-memory SharedPreferences for JVM unit tests (the android.jar stubs do nothing). */
class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor {
            removed += key
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            synchronized(values) {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
        }
    }
}

/** A Context whose getSharedPreferences hands out one in-memory file per name. */
fun contextWithPrefs(): Context {
    val files = HashMap<String, FakeSharedPreferences>()
    return mockk(relaxed = true) {
        every { getSharedPreferences(any(), any()) } answers { files.getOrPut(firstArg()) { FakeSharedPreferences() } }
    }
}
