# Diff-Coverage Compliance Plugin

`Diff-Coverage Compliance Plugin` uses [Diff Coverage Gradle Plugin](https://github.com/form-com/diff-coverage-gradle) to compute code coverage of only modified code, then generates a compliance report.

Why should I use it?
* Automatically configures `diffCoverage` task _(from [com.form.diff-coverage](https://github.com/form-com/diff-coverage-gradle))_ based on working tree and staged changes.
* Supports **ALL** violation rules (instructions, branches, lines, complexity, methods, classes).

## Configuration

<details open="open">
<summary>Groovy</summary>

```groovy
diffCoverageCompliance {
    violationRules {
        minInstructions: 0.75
        minBranches: 0.75
        minLines: 0.75
        minComplexity: 0.75
        minMethods: 0.75
        minClasses: 0.75
    }
}
```
</details>

### Properties

By default, a new task is added (`diffCoverageCompliance`) in addition to the required `diffCoverage` task.
To change this behavior and have the `diffCoverage` task enhanced by this plugin (effectively replacing it),
set project property `diffCoverageCompliance.replaceDiffCoverage` to `true`.
```properties
diffCoverageCompliance.replaceDiffCoverage = true       // default is false
```

## Execution

```shell
./gradlew diffCoverageCompliance
```