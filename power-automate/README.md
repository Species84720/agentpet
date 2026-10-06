# AgentPet AI flow

Android calls `POST /v1/pet-brain` with its companion token. Cloudflare sends only
pet needs, personality, six diary entries, twelve shared memories and a message
to your Power Automate HTTP flow. It never sends agent commands or approvals.
Game values and unlocks are controlled by Android. Memory is capped at twelve
250-character notes. Calls are limited to ten per user per UTC day, at least
15 seconds apart, including failed calls. Local games work without this flow.

## Create the prompt and generate your flow

1. In AI Builder, create and save a text-output prompt using `pet-prompt.txt`.
   Add exactly one **text input** named `PetContext`, and insert its input token
   where the file says to insert it. Leave output as **Text**: the response is
   JSON text, parsed by the generated flow.
2. Create a simple instant cloud flow with a manual trigger and one top-level
   **Run a prompt** action. Select this prompt and put `test` in its PetContext
   input. Save, then **Export → Package (.zip)**. This captures your real prompt
   ID and Dataverse connection. Do not export a solution ZIP for this generator.
3. Generate the HTTP flow package (Python only):

   ```text
   python power-automate/generate_flow.py seed.zip agentpet-brain.zip
   ```

4. In Power Automate use **Import Package (Legacy)**, select the generated ZIP,
   set the flow to **Create as new**, name it **AgentPet Brain**, and select your
   existing AI Builder/Dataverse connection. Save the imported flow.
5. Open its **When an HTTP request is received** trigger. Select **Anyone** as
   the allowed caller, save, and copy the generated HTTP POST URL, including
   its signature. Keep this URL secret: it authorizes calls to your flow.
   The Worker uses this signed URL. A tenant-only/OAuth trigger needs a separate
   Entra authentication integration and is not supported by this version.
6. With Node.js 22 or newer, in the repository's `relay` directory, store the URL interactively:

   ```text
   npx wrangler secret put POWER_AUTOMATE_URL
   ```

   Paste the URL when prompted. Never commit it or place it in Android settings.
   Deploy with `npm run deploy` if the latest Worker is not already deployed.
7. Update Android and press **Ask AI** on Play. Enter a short message. The reply
   appears in the room and the idle floating bubble. Failed configuration shows
   a useful error; check the flow's run history for connector failures.

## Manual alternative / inspect the generated flow

If your tenant blocks legacy imports, create a flow with the HTTP trigger,
**Run a prompt**, and **Response**. Use this trigger schema:

```json
{"type":"object","required":["message","pet","memories"],"properties":{"message":{"type":"string"},"pet":{"type":"object"},"memories":{"type":"array","items":{"type":"string"}}}}
```

Set the prompt's PetContext input expression to `string(triggerBody())`.
Response status is 200, Content-Type is application/json, and body expression is:

```text
json(outputs('Run_a_prompt')?['body/responsev2/predictionOutput/text'])
```

The output contract is:

```json
{"message":"Hello! Shall we explore the garden?","mood":"idle","memory":""}
```

Only idle/celebrate/sleepy moods are accepted. The model cannot award stars,
change stats or approve commands. To delete cloud conversation memory, call
`DELETE /v1/pet-brain` with your companion token (also available in Android).
AI Builder usage consumes your tenant's credits. Protect the flow URL, disable
unnecessary connector retries, and set a tenant spending limit if applicable.

This generator preserves a real exported connector action rather than guessing
tenant IDs. Package generation is tested locally; importing and running it
requires your tenant and a saved prompt, and cannot be verified here.

References: [Run a prompt](https://learn.microsoft.com/en-us/ai-builder/use-a-custom-prompt-in-flow),
[HTTP trigger authentication](https://learn.microsoft.com/en-us/power-automate/oauth-authentication).
