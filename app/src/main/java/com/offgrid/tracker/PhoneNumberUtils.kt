package com.offgrid.tracker

object PhoneNumberUtils {

    /**
     * Telefon numarasını temizler: sadece başındaki '+' ve rakamları korur.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val hasLeadingPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return ""
        return if (hasLeadingPlus) "+$digitsOnly" else digitsOnly
    }

    /**
     * Karşılaştırma için kanonik anahtar üretir.
     * En az 10 haneli numaralarda son 10 haneyi kullanır (örn: +90532... ve 0532... eşleşir).
     */
    fun canonicalKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length >= 10) {
            digits.takeLast(10)
        } else {
            digits
        }
    }

    /**
     * İki telefon numarasının aynı aboneye ait olup olmadığını kontrol eder.
     */
    fun matches(numberA: String, numberB: String): Boolean {
        val keyA = canonicalKey(numberA)
        val keyB = canonicalKey(numberB)
        if (keyA.length < 7 || keyB.length < 7) return false
        return keyA == keyB
    }

    /**
     * Eklenecek telefon numarasının temel format doğrulamasını yapar.
     */
    fun isValidPhoneNumber(raw: String): Boolean {
        val normalized = normalize(raw)
        val digits = normalized.filter { it.isDigit() }
        return digits.length in 10..15
    }

    /**
     * Gönderim için SMS uyumlu temiz format döndürür.
     */
    fun formatForDisplay(raw: String): String {
        val normalized = normalize(raw)
        val digits = normalized.filter { it.isDigit() }
        // Türkiye cep numarası okunaklı format (+90 5XX XXX XX XX)
        return when {
            digits.length == 12 && digits.startsWith("90") ->
                "+90 ${digits.substring(2, 5)} ${digits.substring(5, 8)} ${digits.substring(8, 10)} ${digits.substring(10, 12)}"
            digits.length == 11 && digits.startsWith("0") ->
                "${digits.substring(0, 4)} ${digits.substring(4, 7)} ${digits.substring(7, 9)} ${digits.substring(9, 11)}"
            else -> normalized
        }
    }
}
