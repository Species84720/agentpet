#!/usr/bin/env python3
"""Adapt an exported Run-a-prompt flow, retaining real tenant connection metadata."""
import argparse
import copy
import json
import pathlib
import zipfile


def build(seed, output):
    if pathlib.Path(output).exists():
        raise ValueError("Output exists; choose a new filename.")
    with zipfile.ZipFile(seed) as archive:
        files = {entry.filename: archive.read(entry.filename) for entry in archive.infolist()}
    candidates = []
    for name, data in files.items():
        if not name.endswith(".json"):
            continue
        try:
            document = json.loads(data)
        except (ValueError, UnicodeDecodeError):
            continue
        if not isinstance(document, dict):
            continue
        owner = document.get("properties", document)
        definition = owner.get("definition") if isinstance(owner, dict) else None
        if isinstance(definition, dict):
            candidates.append((name, document, definition))
    if len(candidates) != 1:
        raise ValueError("Export a package containing exactly one simple cloud flow.")
    name, document, definition = candidates[0]
    prompts = [action for action in definition.get("actions", {}).values()
               if action.get("inputs", {}).get("host", {}).get("operationId") == "aibuilderpredict_customprompt"]
    if len(prompts) != 1:
        raise ValueError("The seed must have one top-level Run a prompt action.")
    action = copy.deepcopy(prompts[0])
    parameters = action["inputs"]["parameters"]
    inputs = [key for key in parameters if key.startswith("item/requestv2/")]
    if len(inputs) != 1:
        raise ValueError("Use a saved prompt with exactly one text input (PetContext), filled in the seed flow.")
    parameters[inputs[0]] = "@string(triggerBody())"
    action["runAfter"] = {}
    # Do not let an automatic connector retry consume another AI call.
    action["inputs"]["retryPolicy"] = {"type": "none"}
    definition["triggers"] = {"manual": {"type": "Request", "kind": "Http", "inputs": {
        "schema": {"type": "object", "required": ["message", "pet", "memories"], "properties": {
            "message": {"type": "string"}, "pet": {"type": "object"},
            "memories": {"type": "array", "items": {"type": "string"}}}}}}}
    definition["actions"] = {
        "Run_a_prompt": action,
        "Response": {"type": "Response", "kind": "Http", "runAfter": {"Run_a_prompt": ["Succeeded"]},
                     "inputs": {"statusCode": 200, "headers": {"Content-Type": "application/json"},
                                "body": "@json(outputs('Run_a_prompt')?['body/responsev2/predictionOutput/text'])"}},
        "Failed_response": {"type": "Response", "kind": "Http", "runAfter": {"Run_a_prompt": ["Failed", "TimedOut"]},
                            "inputs": {"statusCode": 502, "body": {"error": "Pet prompt failed"}}}
    }
    definition["outputs"] = {}
    files[name] = json.dumps(document, indent=2).encode()
    with zipfile.ZipFile(output, "x", zipfile.ZIP_DEFLATED) as archive:
        for filename, data in files.items():
            archive.writestr(filename, data)
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("seed", help="Exported Power Automate .zip package")
    parser.add_argument("output", help="New .zip to import into Power Automate")
    args = parser.parse_args()
    try:
        print(build(args.seed, args.output))
    except (ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Cannot generate flow: {error}\n")
