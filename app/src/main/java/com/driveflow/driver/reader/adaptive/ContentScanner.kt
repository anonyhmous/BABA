package com.driveflow.driver.reader.adaptive

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.driveflow.driver.core.util.TextParsers

/**
 * Understands a ride-request screen from its CONTENT, not from view ids or fixed words.
 *
 * It collects every visible text on screen and works out what each one is by its shape:
 *  - the unit word that repeats most (price, accept button, offer chips) is the currency, so the
 *    fare is the top-most amount in that currency;
 *  - a short number + short word with a decimal (or a known km word) is a distance;
 *  - a lone decimal between 1 and 5 is a rating, a lone number in brackets is the trip count.
 * Digits of any script are supported (see TextParsers.normalizeDigits).
 */
object ContentScanner {

    data class Item(val text: String, val bounds: Rect)

    data class Fields(
        val fare: Double?,
        val pickupKm: Double?,
        val rating: Double?,
        val trips: Int?
    )

    private class Quantity(
        val item: Item,
        val value: Double,
        val token: String,
        val hasDecimal: Boolean
    )

    private const val MAX_NODES = 500

    private val KM_TOKEN = Regex("(?i)^(km|kms|kilometers?|kilometres?|كم|كلم|کم|км|公里)$")
    private val METER_TOKEN = Regex("(?i)^(m|meters?|metres?|متر|م|метр)$")
    private val TIME_TOKEN = Regex("(?i)^(s|sec|secs|min|mins|h|hr|hrs|ث|ثانية|ثواني|د|دقيقة|دقائق|ساعة|ساعات|сек|мин)$")
    private val SYMBOLS = Regex("[0-9.,~≈()+\\-:*★]")
    private val WHITESPACE = Regex("\\s+")
    private val DECIMAL = Regex("\\d[.,]\\d")
    private val BRACKET_COUNT = Regex("^\\((\\d{1,6})\\)$")

    /** Every visible text under [root] with its screen bounds. */
    fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>()
        val stack = java.util.ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (!stack.isEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            val text = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!text.isNullOrBlank() && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) out.add(Item(text, rect))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    fun interpret(items: List<Item>): Fields {
        val quantities = items.mapNotNull { toQuantity(it) }

        val tokenCounts = quantities
            .filter { it.token.isNotEmpty() }
            .groupingBy { it.token }
            .eachCount()
        val currencyToken = tokenCounts.filter { it.value >= 3 }.maxByOrNull { it.value }?.key

        val fare = currencyToken?.let { token ->
            quantities.filter { it.token == token }.minByOrNull { it.item.bounds.top }?.value
        }

        val distance = quantities
            .filter { it.token.isNotEmpty() && it.token != currencyToken }
            .filter { KM_TOKEN.matches(it.token) || METER_TOKEN.matches(it.token) || (it.hasDecimal && !TIME_TOKEN.matches(it.token)) }
            .minByOrNull { it.item.bounds.top }
        val pickupKm = distance?.let { if (METER_TOKEN.matches(it.token)) it.value / 1000.0 else it.value }

        val rating = quantities
            .filter { it.token.isEmpty() && it.hasDecimal && it.value in 1.0..5.0 }
            .minByOrNull { it.item.bounds.top }
            ?.value

        val trips = items.firstNotNullOfOrNull { item ->
            BRACKET_COUNT.find(TextParsers.normalizeDigits(item.text))?.groupValues?.get(1)?.toIntOrNull()
        }

        return Fields(fare = fare, pickupKm = pickupKm, rating = rating, trips = trips)
    }

    private fun toQuantity(item: Item): Quantity? {
        val norm = TextParsers.normalizeDigits(item.text)
        if (norm.length > 24) return null
        val value = TextParsers.parseNumber(norm) ?: return null
        val words = norm.replace(SYMBOLS, " ").split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size > 2 || words.any { it.length > 8 }) return null
        return Quantity(
            item = item,
            value = value,
            token = words.joinToString("").lowercase(),
            hasDecimal = DECIMAL.containsMatchIn(norm)
        )
    }
}
