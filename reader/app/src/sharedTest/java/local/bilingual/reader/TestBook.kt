package local.bilingual.reader

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared by the JVM and the instrumented tests (src/sharedTest). Synthetic book package: a short first paragraph ("Hola mundo.") and many long ones to force several pages. */
object TestBook {
    /**
     * With [references] (schema 1.2): "Hola mundo." has its own translation with a footnote; in every later paragraph
     * the translator joined sentences 1 and 2 and left sentence 3 out; the group in paragraph 2 is a free rendering,
     * the one in paragraph 3 is marked uncertain.
     */
    fun bytes(paragraphs: Int = 60, schema: String = "1.0", chapters: Int = 1, references: Boolean = false,
              authorNotes: Boolean = false, heading: Boolean = false, dictionary: Boolean = false,
              modelEntry: Boolean = false, id: String = "test", title: String = "Test"): ByteArray {
        val refs = JSONArray()
        fun ref(text: String?, loose: Boolean = false, uncertain: Boolean = false, notes: List<String> = emptyList()): Int? {
            if (!references) return null
            refs.put(JSONObject().put("text", text ?: JSONObject.NULL).put("loose", loose).put("uncertain", uncertain)
                .put("notes", JSONArray(notes)))
            return refs.length() - 1
        }
        val pars = JSONArray()
        // With [heading] (schema 1.3): the title "Capítulo de prueba" is paragraph 0, annotated like text.
        if (heading) pars.put(paragraph(0, listOf(sentence("c01p000s1", listOf("Capítulo", "de", "prueba"), listOf(" ", " ", ""),
            segments = listOf(seg(0, 2, "Глава проверки")), lexical = listOf(lex(2, "prueba", "проверка", 1))))).put("heading", true))
        pars.put(paragraph(1, listOf(sentence("c01p001s1", listOf("Hola", "mundo", "."), listOf(" ", "", ""),
            segments = listOf(seg(0, 0, "Привет"), seg(1, 2, "мир.")),
            lexical = listOf(lex(1, "mundo", "мир", 1)),
            grammar = listOf(JSONArray(listOf(1, 1, "noun", "Существительное мужского рода."))),
            context = listOf(JSONArray(listOf(0, 0, "Приветствие."))),
            variants = listOf(JSONArray(listOf(1, 1, JSONArray(listOf("мир", "свет")), "оттенок"))),
            expressions = listOf(JSONArray(listOf(0, 1, "hola mundo", "привет, мир"))), ref = ref("Привет, мир.", notes = listOf("n_1")),
            notes = if (authorNotes) listOf("a_1") else emptyList()))))
        val words = "Luego tomaron la jaula en hombros aquellas visiones y la acomodaron en el carro de los bueyes".split(" ")
        for (p in 2..paragraphs) {
            val joined = ref("Тогда призраки подняли клетку на плечи $p.", loose = p == 2, uncertain = p == 3)
            val omitted = ref(null)
            val sentences = (1..3).map { s ->
                val tokens = words + "."
                val ws = tokens.mapIndexed { i, _ -> if (i >= tokens.size - 2) "" else " " }
                sentence("c01p%03ds%d".format(p, s), tokens, ws,
                    segments = listOf(seg(0, 5, "Потом взяли клетку на плечи"), seg(6, 9, "те призраки и её"),
                        seg(10, tokens.lastIndex, "поставили на телегу с волами.")),
                    lexical = listOf(lex(1, "tomar", "взять", p), lex(3, "jaula", "клетка", p)),
                    ref = if (s < 3) joined else omitted)
            }
            pars.put(paragraph(p, sentences))
        }
        val first = JSONObject().put("index", 1).put("title", "Capítulo de prueba").put("paragraphs", pars)
        // With [dictionary] (schema 1.4): the entry of "mundo" for the word card, its gloss chosen from the dictionary.
        // With [modelEntry] (schema 1.5): the same entry in a book built with a model, which chose sense 2 of it.
        if (dictionary || modelEntry) {
            first.put("entries", JSONObject().put("mundo|NOUN", JSONObject().put("headword", "mundo").put("senses", JSONArray(listOf(
                JSONObject().put("id", "1").put("text", "мир, свет").put("gram", "m"),
                JSONObject().put("id", "2").put("text", "общество, люди").put("gram", JSONObject.NULL))))))
            val lexical = pars.getJSONObject(if (heading) 1 else 0).getJSONArray("sentences").getJSONObject(0).getJSONArray("lexical").getJSONObject(0)
            if (dictionary) lexical.put("from", "dictionary") else lexical.put("from", "model").put("sense", "2")
        }
        val footnotes = JSONArray()
        if (references) footnotes.put(JSONObject().put("id", "n_1").put("text", "Hola mundo — первая программа.").put("side", "reference"))
        if (authorNotes) footnotes.put(JSONObject().put("id", "a_1").put("text", "Saludo de los programadores.").put("side", "source"))
        if (references) first.put("references", refs)
        if (footnotes.length() > 0) first.put("footnotes", footnotes)
        val docs = mutableListOf(first)
        for (c in 2..chapters) {
            val second = JSONArray().put(paragraph(1, listOf(sentence("c%02dp001s1".format(c), listOf("Adiós", "."), listOf("", ""),
                segments = listOf(seg(0, 1, "Прощай.")), lexical = listOf(lex(0, "adiós", "прощай", 1)))), c))
            docs += JSONObject().put("index", c).put("title", "Capítulo $c").put("paragraphs", second)
        }
        val list = JSONArray()
        docs.forEach { list.put(JSONObject().put("index", it.getInt("index")).put("title", it.getString("title")).put("file", "chapters/%03d.json".format(it.getInt("index")))) }
        val manifest = JSONObject().put("schema", schema).put("id", id).put("title", title).put("author", "Tester")
            .put("studied", "es").put("support", "ru").put("maxOccurrence", 20).put("chapters", list)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString().toByteArray()); z.closeEntry()
            docs.forEach { d ->
                z.putNextEntry(ZipEntry("chapters/%03d.json".format(d.getInt("index")))); z.write(d.toString().toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun paragraph(n: Int, sentences: List<JSONObject>, chapter: Int = 1) =
        JSONObject().put("id", "c%02dp%03d".format(chapter, n)).put("sentences", JSONArray(sentences))

    private fun seg(first: Int, last: Int, literal: String) = JSONArray(listOf(first, last, literal, JSONObject.NULL))

    private fun lex(t: Int, lemma: String, gloss: String, n: Int) = JSONObject().put("t", t).put("lemma", lemma)
        .put("key", "$lemma|NOUN#1").put("n", n).put("gloss", gloss).put("gram", "м.").put("form", JSONObject.NULL)

    private fun sentence(id: String, tokens: List<String>, ws: List<String>, segments: List<JSONArray>,
                         lexical: List<JSONObject>, grammar: List<JSONArray> = emptyList(), context: List<JSONArray> = emptyList(),
                         variants: List<JSONArray> = emptyList(), expressions: List<JSONArray> = emptyList(), ref: Int? = null,
                         notes: List<String> = emptyList()) = JSONObject()
        .put("id", id).put("tokens", JSONArray(tokens)).put("ws", JSONArray(ws)).put("segments", JSONArray(segments))
        .put("grammar", JSONArray(grammar)).put("context", JSONArray(context)).put("variants", JSONArray(variants))
        .put("expressions", JSONArray(expressions)).put("lexical", JSONArray(lexical))
        .also { if (ref != null) it.put("ref", ref) }
        .also { if (notes.isNotEmpty()) it.put("notes", JSONArray(notes)) }
}
