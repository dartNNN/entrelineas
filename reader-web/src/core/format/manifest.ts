/* Generated from format/manifest.schema.json by npm run types; do not edit. */

/**
 * Book package manifest (manifest.json in a .bbook.zip). The package is a ZIP with manifest.json and one JSON document per chapter (chapter.schema.json). Readers accept any 1.x schema and ignore fields they do not know.
 */
export interface Manifest {
  /**
   * Format version; 1.4 adds the chapter dictionary and the origin of lexical comments.
   */
  schema: string;
  id: string;
  title: string;
  author: string;
  /**
   * Language of the original, ISO 639-1 (es).
   */
  studied: string;
  /**
   * Language of the hints, ISO 639-1 (ru).
   */
  support: string;
  /**
   * Lexical comments are present up to this occurrence of each sense; readers fade them earlier by the owner's threshold.
   */
  maxOccurrence: number;
  /**
   * @minItems 1
   */
  chapters: [
    {
      index: number;
      title: string;
      file: string;
    },
    ...{
      index: number;
      title: string;
      file: string;
    }[]
  ];
}
