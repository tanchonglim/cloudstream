package com.lagradost.cloudstream3.remote

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.XmlResourceParser
import android.util.TypedValue
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen.toStringRes
import com.lagradost.cloudstream3.ui.settings.appLanguages
import com.lagradost.cloudstream3.ui.settings.nameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Exposes the app settings to the remote control app.
 *
 * Titles and grouping are read from the preference XML files at runtime so new settings show up
 * automatically. Settings that use custom dialogs get their choices from [choiceSources], and every
 * other value stored in the default SharedPreferences is exposed in an "Advanced" group, so that
 * every setting on the device can be changed from the phone.
 */
internal object RemoteSettingsCatalog {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APP_NS = "http://schemas.android.com/apk/res-auto"

    /** Keys that must never leave the device or be written remotely. */
    private val hiddenKeys = setOf(
        RemoteControlServer.TOKENS_KEY,
    )

    /** Settings that only take effect after the activity is recreated. */
    private val recreateKeys = setOf(
        "app_theme_key", "primary_color_key", "app_layout_key", "app_locale",
    )

    enum class Kind(val id: String) {
        BOOL("bool"), INT("int"), LONG("long"), FLOAT("float"), STRING("string"),
        STRING_SET("string_set"), CHOICE("choice"), MULTI_CHOICE("multi_choice")
    }

    private data class Choice(val value: String, val label: String)

    private data class Item(
        val key: String,
        val title: String,
        val summary: String?,
        val kind: Kind,
        /** Storage type for CHOICE / MULTI_CHOICE values: "int" or "string". */
        val valueType: String = "string",
        val default: Any? = null,
        val min: Int? = null,
        val max: Int? = null,
        val choices: List<Choice> = emptyList(),
    )

    private data class Group(val title: String, val items: MutableList<Item> = mutableListOf())

    private val screens: List<Pair<Int, Int>> = listOf(
        R.string.category_general to R.xml.settings_general,
        R.string.category_player to R.xml.settings_player,
        R.string.category_ui to R.xml.settings_ui,
        R.string.category_providers to R.xml.settings_providers,
        R.string.category_updates to R.xml.settings_updates,
        R.string.category_account to R.xml.settings_account,
    )

    /** Settings backed by a list dialog in code: key -> (labels array, values array). */
    private val arrayChoices: Map<String, Pair<Int, Int>> = mapOf(
        "dns_key" to (R.array.dns_pref to R.array.dns_pref_values),
        "poster_ui_key" to (R.array.poster_ui_options to R.array.poster_ui_options_values),
        "app_layout_key" to (R.array.app_layout to R.array.app_layout_values),
        "app_theme_key" to (R.array.themes_names to R.array.themes_names_values),
        "primary_color_key" to (R.array.themes_overlay_names to R.array.themes_overlay_names_values),
        "confirm_exit_key" to (R.array.confirm_exit to R.array.confirm_exit_values),
        "video_buffer_length_key" to (R.array.video_buffer_length_names to R.array.video_buffer_length_values),
        "video_buffer_disk_key" to (R.array.video_buffer_size_names to R.array.video_buffer_size_values),
        "video_buffer_size_key" to (R.array.video_buffer_size_names to R.array.video_buffer_size_values),
        "prefer_limit_title_key" to (R.array.limit_title_pref_names to R.array.limit_title_pref_values),
        "software_decoding_key2" to (R.array.software_decoding_switch to R.array.software_decoding_switch_values),
        "automatic_backup_key" to (R.array.periodic_work_names to R.array.periodic_work_values),
        "apk_installer_key" to (R.array.apk_installer_pref to R.array.apk_installer_values),
    )

    /** Defaults for dialog based settings, taken from the code that reads them. */
    private val choiceDefaults: Map<String, Any> = mapOf(
        "dns_key" to 0, "app_layout_key" to -1, "app_theme_key" to "AmoledLight",
        "primary_color_key" to "Normal", "confirm_exit_key" to -1, "video_buffer_length_key" to 0,
        "video_buffer_disk_key" to 0, "video_buffer_size_key" to 0, "prefer_limit_title_key" to 0,
        "software_decoding_key2" to -1, "automatic_backup_key" to 0, "apk_installer_key" to 1,
        "poster_ui_key" to "",
    )

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    // ---------- XML parsing ----------

    private fun XmlResourceParser.attr(context: Context, name: String): String? {
        for (ns in arrayOf(ANDROID_NS, APP_NS)) {
            val res = getAttributeResourceValue(ns, name, 0)
            if (res != 0) {
                return try {
                    context.getString(res)
                } catch (_: Throwable) {
                    null
                }
            }
            getAttributeValue(ns, name)?.let { if (!it.startsWith("@")) return it }
        }
        return null
    }

    private data class XmlPref(
        val tag: String,
        val key: String,
        val title: String,
        val summary: String?,
        val defaultValue: String?,
        val min: Int?,
        val max: Int?,
        val category: String?,
    )

    private fun parseXml(context: Context, @XmlRes xml: Int): List<XmlPref> {
        val out = mutableListOf<XmlPref>()
        val parser = context.resources.getXml(xml)
        val categoryStack = ArrayDeque<String?>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfterLast('.')
                        if (tag == "PreferenceCategory" || tag == "PreferenceScreen") {
                            categoryStack.addLast(parser.attr(context, "title"))
                        } else {
                            val key = parser.attr(context, "key")
                            val title = parser.attr(context, "title")
                            if (key != null && title != null) {
                                out += XmlPref(
                                    tag = tag,
                                    key = key,
                                    title = title,
                                    summary = parser.attr(context, "summary"),
                                    defaultValue = parser.attr(context, "defaultValue"),
                                    min = parser.attr(context, "min")?.toIntOrNull(),
                                    max = parser.attr(context, "max")?.toIntOrNull(),
                                    category = categoryStack.lastOrNull { it != null },
                                )
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfterLast('.')
                        if ((tag == "PreferenceCategory" || tag == "PreferenceScreen") && categoryStack.isNotEmpty()) {
                            categoryStack.removeLast()
                        }
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return out
    }

    // ---------- choices ----------

    @SuppressLint("ResourceType")
    private fun arrayChoice(context: Context, @ArrayRes names: Int, @ArrayRes values: Int): Pair<String, List<Choice>> {
        val res = context.resources
        val labels = res.getStringArray(names)
        val typed = res.obtainTypedArray(values)
        try {
            var allInts = true
            val raw = (0 until typed.length()).map { i ->
                val tv = TypedValue()
                typed.getValue(i, tv)
                if (tv.type in TypedValue.TYPE_FIRST_INT..TypedValue.TYPE_LAST_INT) {
                    tv.data.toString()
                } else {
                    allInts = false
                    typed.getString(i) ?: ""
                }
            }
            val choices = raw.zip(labels.toList()).map { (v, l) -> Choice(v, l) }
            return (if (allInts) "int" else "string") to choices
        } finally {
            typed.recycle()
        }
    }

    private fun qualityChoices(): List<Choice> =
        Qualities.entries.map { it.value }.reversed().filter { it != Qualities.Unknown.value }
            .map { Choice(it.toString(), Qualities.getStringByInt(it)) }

    private fun specialItem(context: Context, key: String, title: String, summary: String?): Item? {
        fun str(@StringRes id: Int) = context.getString(id)
        return when (key) {
            "app_locale" -> Item(
                key, title, summary, Kind.CHOICE, "string", "",
                choices = listOf(Choice("", "System")) +
                        appLanguages.map { (name, code) -> Choice(code, (name to code).nameNextToFlagEmoji()) }
            )

            "quality_pref_key", "quality_pref_mobile_data_key" -> Item(
                key, title, summary, Kind.CHOICE, "int", Qualities.entries.last().value,
                choices = qualityChoices()
            )

            "provider_lang_key" -> {
                val langs = apis.withLock { apis.map { it.lang }.distinct() }
                    .sortedBy { SubtitleHelper.fromTagToLanguageName(it) ?: it }
                Item(
                    key, title, summary, Kind.MULTI_CHOICE, "string", setOf(AllLanguagesName),
                    choices = listOf(Choice(AllLanguagesName, str(R.string.all_languages_preference))) +
                            langs.map { Choice(it, SubtitleHelper.getNameNextToFlagEmoji(it) ?: it) }
                )
            }

            "prefer_media_type_key_2" -> Item(
                key, title, summary, Kind.MULTI_CHOICE, "string",
                TvType.entries.filter { it != TvType.NSFW }.map { it.ordinal.toString() }.toSet(),
                choices = TvType.entries.map { Choice(it.ordinal.toString(), str(it.toStringRes())) }
            )

            "display_sub_key" -> Item(
                key, title, summary, Kind.MULTI_CHOICE, "string",
                DubStatus.entries.map { it.name }.toSet(),
                choices = DubStatus.entries.map { Choice(it.name, it.name) }
            )

            in arrayChoices -> {
                val (names, values) = arrayChoices.getValue(key)
                val (type, choices) = arrayChoice(context, names, values)
                Item(key, title, summary, Kind.CHOICE, type, choiceDefaults[key], choices = choices)
            }

            else -> null
        }
    }

    private fun kindOf(value: Any?): Kind? = when (value) {
        is Boolean -> Kind.BOOL
        is Int -> Kind.INT
        is Long -> Kind.LONG
        is Float -> Kind.FLOAT
        is String -> Kind.STRING
        is Set<*> -> Kind.STRING_SET
        else -> null
    }

    // ---------- building ----------

    private fun buildGroups(context: Context): List<Group> {
        val stored = prefs(context).all
        val seen = HashSet<String>()
        val groups = LinkedHashMap<String, Group>()

        for ((screenTitle, xml) in screens) {
            val screenName = context.getString(screenTitle)
            val parsed = try {
                parseXml(context, xml)
            } catch (_: Throwable) {
                emptyList()
            }
            for (p in parsed) {
                if (p.key in seen || p.key in hiddenKeys) continue
                val item = specialItem(context, p.key, p.title, p.summary) ?: when (p.tag) {
                    "SwitchPreference", "SwitchPreferenceCompat", "CheckBoxPreference" -> Item(
                        p.key, p.title, p.summary, Kind.BOOL, default = p.defaultValue?.toBooleanStrictOrNull() ?: false
                    )

                    "SeekBarPreference" -> Item(
                        p.key, p.title, p.summary, Kind.INT,
                        default = p.defaultValue?.toIntOrNull() ?: 0, min = p.min ?: 0, max = p.max ?: 100
                    )

                    else -> {
                        // Plain "Preference" rows are usually actions (open a page, run a backup).
                        // Only expose them when they actually store a value.
                        val value = stored[p.key] ?: continue
                        val kind = kindOf(value) ?: continue
                        Item(p.key, p.title, p.summary, kind)
                    }
                }
                seen += p.key
                val groupTitle = listOfNotNull(screenName, p.category).joinToString(" · ")
                groups.getOrPut(groupTitle) { Group(groupTitle) }.items += item
            }
        }

        // Everything else stored on the device, so nothing is out of reach.
        val advanced = Group(context.getString(R.string.remote_control_advanced_settings))
        for ((key, value) in stored.toSortedMap()) {
            if (key in seen || key in hiddenKeys) continue
            val kind = kindOf(value) ?: continue
            advanced.items += Item(key, key, null, kind)
        }
        if (advanced.items.isNotEmpty()) groups[advanced.title] = advanced

        return groups.values.toList()
    }

    private fun currentValue(stored: Map<String, *>, item: Item): Any? = stored[item.key] ?: item.default

    private fun Any?.toJsonValue(): Any = when (this) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray(this.map { it.toString() }.sorted())
        is Float -> this.toDouble()
        else -> this
    }

    fun listJson(context: Context): JSONObject {
        val stored = prefs(context).all
        val groups = JSONArray()
        for (group in buildGroups(context)) {
            val items = JSONArray()
            for (item in group.items) {
                items.put(JSONObject().apply {
                    put("key", item.key)
                    put("title", item.title)
                    item.summary?.let { put("summary", it) }
                    put("type", item.kind.id)
                    put("valueType", item.valueType)
                    put("value", currentValue(stored, item).let {
                        if ((item.kind == Kind.CHOICE) && it != null) it.toString() else it.toJsonValue()
                    })
                    item.min?.let { put("min", it) }
                    item.max?.let { put("max", it) }
                    put("restart", item.key in recreateKeys)
                    if (item.choices.isNotEmpty()) {
                        put("options", JSONArray().apply {
                            item.choices.forEach { c -> put(JSONObject().put("value", c.value).put("label", c.label)) }
                        })
                    }
                })
            }
            groups.put(JSONObject().put("title", group.title).put("items", items))
        }
        return JSONObject().put("groups", groups)
    }

    /**
     * Writes a single setting.
     * @return true if the activity should be recreated for the change to be visible.
     */
    fun write(context: Context, key: String, value: Any?): Boolean {
        if (key in hiddenKeys || key.isBlank()) throw MiniHttp.HttpException(403, "Setting is not writable")
        val item = buildGroups(context).asSequence().flatMap { it.items }.firstOrNull { it.key == key }
        val existing = prefs(context).all[key]
        val kind = item?.kind ?: kindOf(existing)
        ?: throw MiniHttp.HttpException(404, "Unknown setting $key")

        fun bad(): Nothing = throw MiniHttp.HttpException(400, "Invalid value for $key")
        fun asStringSet(v: Any?): Set<String> = when (v) {
            is JSONArray -> (0 until v.length()).map { v.get(it).toString() }.toSet()
            is Collection<*> -> v.map { it.toString() }.toSet()
            else -> bad()
        }

        prefs(context).edit(commit = true) {
            when (kind) {
                Kind.BOOL -> putBoolean(key, when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: bad()
                    else -> bad()
                })

                Kind.INT -> putInt(key, (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: bad())
                Kind.LONG -> putLong(key, (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: bad())
                Kind.FLOAT -> putFloat(key, (value as? Number)?.toFloat() ?: value?.toString()?.toFloatOrNull() ?: bad())
                Kind.STRING -> putString(key, value?.toString() ?: bad())
                Kind.STRING_SET -> putStringSet(key, asStringSet(value))
                Kind.CHOICE -> {
                    val v = value?.toString() ?: bad()
                    if (item != null && item.choices.isNotEmpty() && item.choices.none { it.value == v }) bad()
                    if (item?.valueType == "int") putInt(key, v.toIntOrNull() ?: bad()) else putString(key, v)
                }

                Kind.MULTI_CHOICE -> putStringSet(key, asStringSet(value))
            }
        }
        return key in recreateKeys
    }
}
