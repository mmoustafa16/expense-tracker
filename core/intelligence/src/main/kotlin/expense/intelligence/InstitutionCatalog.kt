package expense.intelligence

import org.json.JSONObject

/**
 * Public institution aliases shipped as data.
 * Replacing [institutions.json] does not change the parser or the semantic model.
 * The file is read from the app resources. Nothing is downloaded.
 */
object InstitutionCatalog {
    fun bundled(): List<RegisteredSender> = Holder.records

    private object Holder {
        val records: List<RegisteredSender> = load()
    }

    private fun load(): List<RegisteredSender> {
        val stream = InstitutionCatalog::class.java.getResourceAsStream(
            "/expense/intelligence/institutions.json",
        ) ?: return emptyList()
        val json = JSONObject(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        val rows = json.optJSONArray("institutions") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                val name = row.optString("name").trim()
                val senders = row.optJSONArray("senders") ?: continue
                val aliases = buildSet {
                    for (senderIndex in 0 until senders.length()) {
                        val alias = senders.optString(senderIndex).trim()
                        if (alias.isNotEmpty()) add(alias)
                    }
                }
                if (id.isEmpty() || name.isEmpty() || aliases.isEmpty()) continue
                add(RegisteredSender(id, name, aliases))
            }
        }
    }
}
