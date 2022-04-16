# Diff-Coverage Compliance Plugin

`Diff-Coverage Compliance Plugin` uses the [Diff Coverage Gradle Plugin (com.form.coverage.gradle.DiffCoverageTask)](https://github.com/form-com/diff-coverage-gradle) to compute code coverage of only modified code, then generates a compliance report.

Why should I use it?

* Automatically configures `diffCoverage` task  based on working tree and staged changes.
* Supports violation rules for **ALL** JaCoCo report metrics (`minInstructions`, `minBranches`, `minLines`, `minComplexity`, `minMethods`, and `minClasses`).


## Configuration

```groovy
diffCoverageCompliance {
    reportName = project.projectDir.name
    diffBase = project.properties.getOrDefault('diffCoverageCompliance.diffBase', 'main')
    diffOutputPath = Paths.get("${project.buildDir}", 'diffCoverageCompliance', 'patch.diff')
  
    reports {
        baseReportDir = Paths.get('build', 'reports', 'jacoco')
    
        fullCoverageReport = false
    
        csv = false
        xml = false
        html = false
    }
  
    violationRules {
        failOnViolation = false

        minInstructions: 0.75
        minBranches: 0.75
        minLines: 0.75
        minComplexity: 0.75
        minMethods: 0.75
        minClasses: 0.75
    }
}
```


### Properties

By default, a new `diffCoverageCompliance` task is added in addition to the required `diffCoverage` task.
To change this behavior and instead have the `diffCoverage` task enhanced by this plugin (effectively replacing it),
set project property `diffCoverageCompliance.replaceDiffCoverage` to `true`.

```properties
diffCoverageCompliance.replaceDiffCoverage = true       // default is false
```

## Execution

```shell
./gradlew diffCoverageCompliance
```

### Command-line Options

> Note: The `diffCoverageCompliance.replaceDiffCoverage` property must be set to `false` in order to use any command-line options.
> This is because the command-line options apply only to the `diffCoverageCompliance` task --- not the `diffCoverage` task.

```shell
./gradlew help --task diffCoverageCompliance
```

