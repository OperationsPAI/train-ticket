import { readFileSync } from "node:fs";
import { join } from "node:path";

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
export function readJsonSettings(dir: string, file: string): Readonly<Record<string, unknown>> {
  const path = join(dir, file);
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (error) {
    throw new Error(`settings file ${file} could not be read at ${path}: ${describe(error)}`);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`settings file ${file} is not valid JSON: ${describe(error)}`);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error(`settings file ${file} must hold a JSON object`);
  }
  return deepFreeze(parsed as Record<string, unknown>);
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function deepFreeze<T>(value: T): T {
  if (value === null || typeof value !== "object") {
    return value;
  }
  Object.freeze(value);
  for (const entry of Object.values(value as Record<string, unknown>)) {
    deepFreeze(entry);
  }
  return value;
}
