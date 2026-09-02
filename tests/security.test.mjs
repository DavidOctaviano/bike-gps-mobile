import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";

async function sourceFiles(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.name === "build") continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await sourceFiles(path));
    else result.push(path);
  }
  return result;
}

test("Android source never embeds the Strava client secret", async () => {
  const files = await sourceFiles("app");
  const text = (await Promise.all(files.filter(file => /\.(java|xml|kts|properties)$/.test(file))
    .map(file => readFile(file, "utf8")))).join("\n");
  assert.equal(/client_secret/i.test(text), false);
  assert.equal(/STRAVA_CLIENT_SECRET/.test(text), false);
});
