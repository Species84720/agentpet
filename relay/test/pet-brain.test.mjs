import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/pet-brain.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const { petBrain } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);
const request = body => new Request('https://room/brain', { method: 'POST', body: JSON.stringify(body) });
function store(initial = {}) {
  const data = new Map(Object.entries(initial));
  const storage = { get: async key => structuredClone(data.get(key)), put: async (key, value) => data.set(key, structuredClone(value)), delete: async key => data.delete(key) };
  storage.transaction = async callback => callback(storage);
  return storage;
}

test('unconfigured brain does not call AI', async () => {
  const response = await petBrain(request({ message: 'Hello' }), store());
  assert.equal(response.status, 503);
});

test('context is limited and response cannot grant care or approvals', async t => {
  let sent;
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    sent = JSON.parse(options.body);
    return Response.json({ message: 'A'.repeat(1000), mood: 'approve', memory: 'New memory', stars: 9000, decision: 'allow' });
  });
  const storage = store({ brain_memories: Array.from({ length: 12 }, (_, i) => `memory ${i}`) });
  const response = await petBrain(request({ message: 'Hello', execution: 'secret command', pet: { energy: 500, hunger: -5, token: 'secret', diary: Array(20).fill('x'.repeat(300)) } }), storage, 'https://flow.example/secret');
  const result = await response.json();
  assert.equal(result.message.length, 800);
  assert.equal(result.mood, 'idle');
  assert.equal(result.stars, undefined);
  assert.equal(result.decision, undefined);
  assert.equal(sent.execution, undefined);
  assert.equal(sent.pet.token, undefined);
  assert.equal(sent.pet.energy, 100);
  assert.equal(sent.pet.hunger, 0);
  assert.equal(sent.pet.diary.length, 6);
  assert.equal(sent.pet.diary[0].length, 250);
  assert.equal((await storage.get('brain_memories')).length, 12);
  assert.equal((await storage.get('brain_memories')).at(-1), 'New memory');
});

test('daily cap and cooldown reject calls without invoking the flow', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => { calls++; return Response.json({ message: 'Hello' }); });
  const today = new Date().toISOString().slice(0, 10);
  const capped = await petBrain(request({ message: 'Hello' }), store({ brain_usage: { day: today, count: 10, last: Date.now() - 30_000 } }), 'https://flow.example');
  const cooldown = await petBrain(request({ message: 'Hello' }), store({ brain_usage: { day: today, count: 1, last: Date.now() } }), 'https://flow.example');
  assert.equal(capped.status, 429);
  assert.equal(cooldown.status, 429);
  assert.equal(calls, 0);
});

test('flow failures consume quota; clearing memory does not reset quota', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response('failed', { status: 500 }));
  const storage = store({ brain_memories: ['old'] });
  const response = await petBrain(request({ message: 'Hello' }), storage, 'https://flow.example');
  assert.equal(response.status, 502);
  assert.equal((await storage.get('brain_usage')).count, 1);
  await petBrain(new Request('https://room/brain', { method: 'DELETE' }), storage);
  assert.equal(await storage.get('brain_memories'), undefined);
  assert.equal((await storage.get('brain_usage')).count, 1);
});
