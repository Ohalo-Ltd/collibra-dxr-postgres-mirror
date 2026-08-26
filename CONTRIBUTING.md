# Contributing

Issues and pull requests are welcome, but note the [LICENSE](LICENSE): this is
source-available software owned by Ohalo Ltd, and contributions are accepted
under its terms.

The build/release tooling for these workflows (the `collibra-workflower`
harness, which packages `workflows/<name>/` into Collibra Workflow Designer
ZIPs) is currently Ohalo-internal. External contributors can edit the Groovy /
BPMN / form sources directly; Ohalo maintainers will build and test the change
against a Collibra instance before merging. Consume the workflows from the
[Releases](../../releases) page — every release carries a ready-to-import
bundle and a deployment guide.

Please keep scripts free of credentials and instance-specific hostnames:
anything environment-specific belongs in a hidden configuration variable
(`<flowable:formProperty readable="false">`) with a placeholder default.
