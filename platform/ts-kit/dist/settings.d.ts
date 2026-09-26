/**
 * Read a service's own settings file.
 *
 * A value that decides how a service behaves is data rather than code: it moves
 * when the programme it belongs to moves, and moving it must not take a new
 * image. The file is read once, at startup, by the service that owns the values,
 * which validates what it keeps and hands the rest on frozen, so neither a
 * request nor an event can move a value that decides how it is served.
 *
 * Failing here is deliberate. A service whose settings cannot be read has no
 * default to fall back on, because a default nobody stated is a value nobody
 * agreed to; the alternative is a process that answers from a value that is not
 * the one the programme states.
 */
export declare function readJsonSettings(dir: string, file: string): Readonly<Record<string, unknown>>;
