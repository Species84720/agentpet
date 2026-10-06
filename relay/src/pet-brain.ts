/** Conversational AI may suggest activities, but never mutates care or approvals. */
export async function petBrain(request: Request, storage: DurableObjectStorage, flowUrl?: string): Promise<Response> {
  const reply = (body: unknown, status = 200) => Response.json(body, { status, headers: { "cache-control": "no-store" } });
  if (request.method === "GET") return reply({ configured: !!flowUrl });
  if (request.method === "DELETE") {
    await storage.transaction(async txn => {
      await txn.put("brain_memory_revision", (await txn.get<number>("brain_memory_revision") || 0) + 1);
      await txn.delete("brain_memories");
    });
    return reply({ cleared: true });
  }
  if (!flowUrl) return reply({ error: "The Power Automate flow has not been configured yet." }, 503);
  const input = await request.json<Record<string, unknown>>().catch(() => null);
  if (!input || typeof input.message !== "string" || !input.message.trim() || input.message.length > 500) return reply({ error: "A message of 1–500 characters is required." }, 400);
  const now = Date.now(), day = new Date(now).toISOString().slice(0, 10);
  const reservation = await storage.transaction(async txn => {
    const usage = await txn.get<{ day: string; count: number; last: number }>("brain_usage");
    if (usage && now - usage.last < 15_000) return { error: "Let your pet think for 15 seconds before asking again.", count: 0 };
    if (usage?.day === day && usage.count >= 10) return { error: "Your pet has used today's 10 AI conversations. Local games still work.", count: 0 };
    const count = usage?.day === day ? usage.count + 1 : 1;
    await txn.put("brain_usage", { day, count, last: now });
    return { error: "", count };
  });
  if (reservation.error) return reply({ error: reservation.error }, 429);
  const source = input.pet && typeof input.pet === "object" ? input.pet as Record<string, unknown> : {};
  const pet: Record<string, unknown> = {};
  for (const key of ["hunger", "happiness", "cleanliness", "energy", "bond", "stars"]) pet[key] = Math.min(100, Math.max(0, Number(source[key]) || 0));
  pet.personality = String(source.personality || "Scout").slice(0, 30);
  pet.sleeping = source.sleeping === true;
  pet.diary = Array.isArray(source.diary) ? source.diary.slice(0, 6).map(x => String(x).slice(0, 250)) : [];
  const { memories, memoryRevision } = await storage.transaction(async txn => ({
    memories: await txn.get<string[]>("brain_memories") || [],
    memoryRevision: await txn.get<number>("brain_memory_revision") || 0,
  }));
  try {
    const response = await fetch(flowUrl, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ message: input.message.trim(), pet, memories }), signal: AbortSignal.timeout(60_000),
    });
    if (!response.ok) return reply({ error: "Power Automate could not answer. Check its flow run history." }, 502);
    const raw: any = await response.json();
    if (!raw || typeof raw.message !== "string" || !raw.message.trim()) return reply({ error: "The flow returned an invalid pet response." }, 502);
    const message = raw.message.trim().slice(0, 800);
    const mood = ["idle", "celebrate", "sleepy"].includes(raw.mood) ? raw.mood : "idle";
    const memory = typeof raw.memory === "string" ? raw.memory.trim().slice(0, 250) : "";
    if (memory) await storage.transaction(async txn => {
      if ((await txn.get<number>("brain_memory_revision") || 0) !== memoryRevision) return;
      const current = await txn.get<string[]>("brain_memories") || [];
      await txn.put("brain_memories", [...current, memory].slice(-12));
    });
    return reply({ message, mood, remainingToday: 10 - reservation.count });
  } catch {
    return reply({ error: "The pet's AI is unavailable right now. Try again later." }, 502);
  }
}
