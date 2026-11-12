/* Generated from format/chapter.schema.json by npm run types; do not edit. */

/**
 * Chapter document (chapters/NNN.json in a .bbook.zip). Token ranges are inclusive indices into the sentence's tokens. The original text is the tokens with their trailing whitespace (ws); every annotation is a separate layer over it.
 */
export interface Chapter {
  index: number;
  title: string;
  /**
   * The published translation, one entry per aligned group of sentences (1.1); a sentence points to its group by ref.
   */
  references?: {
    /**
     * null where the translator left the sentences out.
     */
    text: string | null;
    /**
     * A free rendering.
     */
    loose?: boolean;
    /**
     * The text may not render these sentences.
     */
    uncertain?: boolean;
    /**
     * Ids of the translator's footnotes in it.
     */
    notes?: string[];
  }[];
  /**
   * Footnotes (1.2): the translator's (reference) and the author's (source).
   */
  footnotes?: {
    id: string;
    text: string;
    side: "reference" | "source";
  }[];
  /**
   * Dictionary entries of the chapter's words (1.4), keyed like lexical comments without the sense (lemma|POS), for the word card. A book built without a model has them since 1.4, a book built with one since 1.5.
   */
  entries?: {
    [k: string]: Entry;
  };
  paragraphs: {
    id: string;
    /**
     * The chapter title as an annotated paragraph (1.3).
     */
    heading?: boolean;
    sentences: Sentence[];
  }[];
}
export interface Entry {
  headword: string;
  senses: {
    id: string;
    text: string;
    gram?: string | null;
  }[];
}
export interface Sentence {
  id: string;
  /**
   * @minItems 1
   */
  tokens: [string, ...string[]];
  /**
   * Whitespace after each token; same length as tokens.
   */
  ws: string[];
  /**
   * Sense groups with their literal translation and an optional literary one: [first, last, literal, literary|null].
   */
  segments: [unknown, unknown, unknown] | [unknown, unknown, unknown, unknown][];
  /**
   * [first, last, topic key, note].
   */
  grammar: [unknown, unknown, unknown, unknown, ...unknown[]][];
  /**
   * [first, last, note].
   */
  context: [unknown, unknown, unknown, ...unknown[]][];
  /**
   * [first, last, [options], note].
   */
  variants: [unknown, unknown, unknown, ...unknown[]][];
  /**
   * [first, last, expression, meaning].
   */
  expressions: [unknown, unknown, unknown, unknown, ...unknown[]][];
  lexical: Lexical[];
  /**
   * Index into references.
   */
  ref?: number | null;
  /**
   * Ids of the author's footnotes in this sentence.
   */
  notes?: string[];
}
/**
 * A word comment: the sense used here and its occurrence number in the book.
 */
export interface Lexical {
  /**
   * Token index.
   */
  t: number;
  lemma: string;
  /**
   * lemma|POS#sense: what familiar marks and fading count.
   */
  key: string;
  /**
   * Occurrence of this sense in the book.
   */
  n: number;
  gloss: string;
  gram?: string | null;
  form?: string | null;
  /**
   * Who chose the sense (1.4): the language model, or the dictionary without a model (then it may not fit the context).
   */
  from?: "model" | "dictionary";
  /**
   * Id of the chosen sense in the chapter's dictionary entry (1.5), when the key's sense id is the model's own numbering; otherwise the part of the key after '#'.
   */
  sense?: string;
}
