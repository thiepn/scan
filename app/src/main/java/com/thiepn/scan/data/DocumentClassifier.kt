package com.thiepn.scan.data

data class DocumentTypeSuggestion(
    val type: DocumentType,
    val score: Int,
    val reasons: List<String>
)

object DocumentClassifier {
    private data class Rule(
        val type: DocumentType,
        val phrases: List<Pair<String, Int>>
    )

    private val rules = listOf(
        Rule(
            DocumentType.RECEIPT,
            listOf(
                "receipt" to 5,
                "subtotal" to 3,
                "total" to 1,
                "cash" to 2,
                "change" to 2,
                "vat" to 2,
                "tax" to 1,
                "thank you for your purchase" to 4
            )
        ),
        Rule(
            DocumentType.INVOICE,
            listOf(
                "invoice" to 6,
                "invoice number" to 4,
                "amount due" to 4,
                "payment terms" to 3,
                "due date" to 2,
                "iban" to 2,
                "bill to" to 3
            )
        ),
        Rule(
            DocumentType.ID,
            listOf(
                "identity card" to 6,
                "passport" to 6,
                "date of birth" to 3,
                "nationality" to 3,
                "surname" to 1,
                "given names" to 2,
                "expiry" to 2,
                "expires" to 2
            )
        ),
        Rule(
            DocumentType.FORM,
            listOf(
                "form" to 4,
                "please complete" to 4,
                "please fill" to 4,
                "signature" to 2,
                "applicant" to 2,
                "application" to 2,
                "date:" to 1
            )
        ),
        Rule(
            DocumentType.NOTES,
            listOf(
                "notes" to 4,
                "lecture" to 2,
                "todo" to 2,
                "to do" to 2,
                "summary" to 1,
                "chapter" to 1
            )
        ),
        Rule(
            DocumentType.LETTER,
            listOf(
                "dear " to 3,
                "sincerely" to 3,
                "yours faithfully" to 4,
                "yours sincerely" to 4,
                "regards" to 2,
                "subject:" to 2
            )
        ),
        Rule(
            DocumentType.BUSINESS_CARD,
            listOf(
                "mobile" to 2,
                "phone" to 1,
                "email" to 2,
                "www." to 2,
                ".com" to 1,
                "linkedin" to 2,
                "manager" to 1,
                "director" to 1
            )
        ),
        Rule(
            DocumentType.BOOK,
            listOf(
                "isbn" to 6,
                "copyright" to 2,
                "contents" to 2,
                "chapter" to 2,
                "publisher" to 2
            )
        ),
        Rule(
            DocumentType.WHITEBOARD,
            listOf(
                "whiteboard" to 6,
                "brainstorm" to 3,
                "diagram" to 2,
                "agenda" to 2
            )
        ),
        Rule(
            DocumentType.CERTIFICATE,
            listOf(
                "certificate" to 6,
                "certifies that" to 5,
                "awarded to" to 4,
                "completion" to 2,
                "achievement" to 2
            )
        )
    )

    fun suggest(title: String, ocrText: String): DocumentTypeSuggestion? {
        val haystack = "$title\\n$ocrText".lowercase()
        if (haystack.isBlank()) return null

        val scored = rules.map { rule ->
            val matched = rule.phrases.filter { (phrase, _) -> phrase in haystack }
            DocumentTypeSuggestion(
                type = rule.type,
                score = matched.sumOf { it.second },
                reasons = matched.sortedByDescending { it.second }.map { it.first }
            )
        }.sortedByDescending { it.score }

        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.score ?: 0

        if (best.score < 4) return null
        if (best.score - second < 2 && best.score < 7) return null
        return best
    }
}
