import importlib.util
import json
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("generator", pathlib.Path(__file__).with_name("generate_flow.py"))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class FlowGeneratorTests(unittest.TestCase):
    def seed(self, path, extra_input=False):
        parameters = {"recordId": "actual-prompt-id", "item/requestv2/PetContext": "test"}
        if extra_input:
            parameters["item/requestv2/Other"] = "test"
        definition = {"parameters": {"$connections": {"type": "Object"}}, "triggers": {}, "actions": {
            "Existing_prompt": {"type": "OpenApiConnection", "inputs": {"parameters": parameters, "host": {
                "operationId": "aibuilderpredict_customprompt", "connectionName": "tenant-connection"}}}}}
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Microsoft.Flow/flows/id/definition.json", json.dumps({"properties": {"definition": definition, "connectionReferences": {"tenant-connection": "actual-connection"}}}))
            archive.writestr("manifest.json", '{"displayName":"seed"}')

    def test_preserves_connections_and_builds_http_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            seed, output = pathlib.Path(directory) / "seed.zip", pathlib.Path(directory) / "flow.zip"
            self.seed(seed)
            generator.build(seed, output)
            with zipfile.ZipFile(output) as archive:
                props = json.loads(archive.read("Microsoft.Flow/flows/id/definition.json"))["properties"]
                definition = props["definition"]
                self.assertEqual(props["connectionReferences"]["tenant-connection"], "actual-connection")
                action = definition["actions"]["Run_a_prompt"]
                self.assertEqual(action["inputs"]["parameters"]["recordId"], "actual-prompt-id")
                self.assertEqual(action["inputs"]["parameters"]["item/requestv2/PetContext"], "@string(triggerBody())")
                self.assertEqual(definition["triggers"]["manual"]["kind"], "Http")
                self.assertEqual(definition["actions"]["Response"]["inputs"]["statusCode"], 200)
                self.assertEqual(archive.read("manifest.json"), b'{"displayName":"seed"}')

    def test_rejects_ambiguous_prompt_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = pathlib.Path(directory) / "seed.zip"
            self.seed(seed, extra_input=True)
            with self.assertRaises(ValueError):
                generator.build(seed, pathlib.Path(directory) / "flow.zip")


if __name__ == "__main__":
    unittest.main()
