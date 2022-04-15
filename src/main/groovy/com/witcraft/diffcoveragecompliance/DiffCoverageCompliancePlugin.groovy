package com.witcraft.diffcoveragecompliance

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FromString
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryTree
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskCollection
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.internal.ExecException
import org.gradle.testing.jacoco.tasks.JacocoReport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Collectors
import java.util.stream.Stream

import static com.witcraft.diffcoveragecompliance.CoverageMetric.LINE
import static com.witcraft.diffcoveragecompliance.CoveragePercentFormat.COMPACT
import static com.witcraft.diffcoveragecompliance.GrammarFormat.grammar

class DiffCoverageCompliancePlugin implements Plugin<Project> {
    public static final String DIFF_COVERAGE_COMPLIANCE_NAME = 'diffCoverageCompliance'
    protected static final String DIFF_COVERAGE_NAME = 'diffCoverage'
    private static final List<String> ALL_DIFF_COVERAGE_NAMES = [DIFF_COVERAGE_NAME, DIFF_COVERAGE_COMPLIANCE_NAME]
    private static final String GIT_COMMAND_NAME = "git"
    private static final Pattern PATTERN_GIT_DIFF_NEW_FILE = ~/^\+{3}\s+(?<quote>["']?)[ab][\/\\](?<path>.+)\1\t?.*$/
    private static final Pattern PATTERN_SKIP_TASK_MESSAGE_ENDING = ~/([^a-zA-Z\d\s]) Skipping task$/
    private List<String> IGNORED_JACOCO_TASK_PATHS = [] //[':jacocoTestReport']
    private Project project
    private boolean replaceDiffCoverage
    private DiffCoverageCompliancePluginExtension extension
    private Map<String, Object> initialReportConfig
    private boolean displayOrderUsesEnum = false

    DiffCoverageCompliancePlugin() {
        initialReportConfig = new HashMap<>()
    }

    void initFrom(Project project) {
        this.project = project
        this.extension = project.extensions.create(DIFF_COVERAGE_COMPLIANCE_NAME, DiffCoverageCompliancePluginExtension, project)
        this.replaceDiffCoverage = Boolean.valueOf(project.properties.getOrDefault('diffCoverageCompliance.replaceDiffCoverage', false) as String)
    }

    @Override
    void apply(Project project) {
        initFrom(project)
        if (replaceDiffCoverage) {
            project.logger.info "DiffCoverageCompliancePlugin has configured task 'diffCoverageCompliance' to replace task 'diffCoverage'."
        }

        String expectedTaskName = (replaceDiffCoverage ? DIFF_COVERAGE_NAME : DIFF_COVERAGE_COMPLIANCE_NAME)

        project.apply plugin: 'jacoco'
        project.apply plugin: 'com.form.diff-coverage'

        registerNewTasks(project)

        if (!isTaskInStartParameter(project, expectedTaskName)) {
            return
        }

        if (extension.diffBase.getOrElse('').empty) {
            project.logger.quiet "Plugin 'DiffCoverageCompliancePlugin' is missing required property \"diffBase\". Skipping project"
            skipDiffCoverageTasks()
            return
        }

        Path pathToGit = which(GIT_COMMAND_NAME)
        if (Objects.isNull(pathToGit)) {
            skipDiffCoverageTasks("Could not find '${GIT_COMMAND_NAME}'")
            return
        }
        Path gitRepoPath = findGitRepoPath(project)
        if (Objects.isNull(gitRepoPath)) {
            skipDiffCoverageTasks("Project not within a git repository")
            return
        }

        configureDiffCoverageTask(project)
    }

    private Map<Project, Set<Path>> getProjectPathMapFromDiff(Path diffPath) {
        if (!Files.exists(diffPath)) {
            if (!generateWorkingTreeDiff(diffPath, extension.diffBase.get())) {
                return null
            }
        }
        return getProjectPathMap(getAffectedFilesFromDiff(diffPath))
    }

    private static boolean isTaskInStartParameter(Project project, String taskName) {
        List<String> startArgs = project.gradle.startParameter.taskNames
        int taskIndex = startArgs.indexOf(taskName)
        if (taskIndex < 0) {
            return false
        } else if (taskIndex < 2) {
            return true
        } else {
            return (startArgs.subList(taskIndex - 2, taskIndex) != ['help', '--task'])
        }
    }

    static <T> T resolveProvided(T value) { value }

    static <T> T resolveProvided(Provider<T> provider) { provider.orNull }

    private void skipDiffCoverageTasks() {
        skipDiffCoverageTasks(null)
    }

    private void skipDiffCoverageTasks(String message) {
        TaskCollection tasks
        if (replaceDiffCoverage) {
            tasks = project.tasks.matching { (it as Task).name == DIFF_COVERAGE_NAME }
        } else {
            tasks = project.tasks.matching { ALL_DIFF_COVERAGE_NAMES.contains((it as Task).name) }
        }
        skipTasks(tasks, message)
    }

    @SuppressWarnings('unused')
    private void skipTasks(TaskCollection tasks) {
        skipTasks(tasks, null)
    }

    private void skipTasks(TaskCollection tasks, String message) {
        tasks.configureEach { (it as Task).enabled = false }
        if (message != null && !message.empty) {
            if (!(message =~ PATTERN_SKIP_TASK_MESSAGE_ENDING).find()) {
                message += ". Skipping ${grammar(tasks.size(), 'task', 'tasks')}"
            }
            project.logger.quiet message
        }
    }

    private void registerNewTasks(Project project) {
        if (replaceDiffCoverage) {
            return
        }

        project.tasks.register(DIFF_COVERAGE_COMPLIANCE_NAME, DiffCoverageComplianceTask).configure { DiffCoverageComplianceTask task ->
            task.outputs.upToDateWhen { false }

            if (isTaskInStartParameter(project, DIFF_COVERAGE_COMPLIANCE_NAME)) {
                initConfigureDiffCoverageTask(task)

                task.dependsOn project.tasks.named('diffCoverage')
            }

            doLast {
                runDiffCoverageCompliancePlugin()
            }
        }
    }

    private void runDiffCoverageCompliancePlugin() {
        project.logger.debug "DiffCoverageComplianceConfig(color=${extension.color.get()}, diffBase=${extension.diffBase.get()}, diffOutputPath=${extension.diffOutputPath.get()}, reports=${extension.reports}, violationRules=${extension.violationRules})"
        Task diffCoverageTask = project.tasks.getByName('diffCoverage')
        Path diffPath = extension.diffOutputPath.get()
        Map<Project, Set<Path>> projectPathMap = getProjectPathMapFromDiff(diffPath)
        DiffCoverageComplianceResult results = evaluateCoverageCompliance(diffCoverageTask, projectPathMap, this::collectIntoCoverageResults)
        project.logger.debug "${results}"

        postTaskCleanup(results)

        StringBuilder outputBuffer = new StringBuilder()
        printCoverageResults(results, outputBuffer)

        outputBuffer.eachLine {
            project.logger.quiet it
        }
        Paths.get(project.buildDir.toString(), DIFF_COVERAGE_COMPLIANCE_NAME, 'compliance.log').withWriter('utf-8') {
            it.append(outputBuffer)
        }

        evaluateViolationsAndRespond(results)
    }

    private initConfigureDiffCoverageTask(Task task) {
        project.ext.currentBranch = doGit(project, 'symbolic-ref', '--short', 'HEAD')
        String[] currentBranchInfo = doGit(project, 'log', '-n', '1', '--format=%H%n%at%n%ar')?.split(/\n+/)
        if (currentBranchInfo?.length != 3) {
            project.logger.error "Failed to gather commit information for branch '${project.ext.currentBranch}'"
            task.enabled = false
            return
        }
        def (String hash, String date, String dateRelative) = currentBranchInfo

        project.ext.diffBaseHash = hash
        project.ext.diffBaseDate = new Date(1000 * Long.valueOf(date))
        project.ext.diffBaseDateRelative = dateRelative
    }

    private void configureDiffCoverageTask(Project project) {
        project.tasks.named('diffCoverage').configure { Task task ->
            task.outputs.upToDateWhen { false }

            String expectedTaskName = (replaceDiffCoverage ? DIFF_COVERAGE_NAME : DIFF_COVERAGE_COMPLIANCE_NAME)

            if (isTaskInStartParameter(project, expectedTaskName)) {
                if (replaceDiffCoverage) {
                    initConfigureDiffCoverageTask(task)
                }
                String diffBase = extension.diffBase.get()

                Path gitRepoPath = findGitRepoPath(project)
                List<Path> filesInDiff = getDiffNameStatus(diffBase).collect { gitRepoPath.resolve(it[0]) }
                getAffectedProjectsFromPaths(filesInDiff).each {
                    task.dependsOn it.tasks.withType(JacocoReport).matching { !IGNORED_JACOCO_TASK_PATHS.contains(it.path) }
                }
            }

            task.onlyIf {
                Path diffPath = extension.diffOutputPath.get()
                String diffBase = extension.diffBase.get()

                task.logger.info "Generating diff to \"${diffPath}\""

                if (!generateWorkingTreeDiff(diffPath, diffBase)) {
                    task.logger.error "Failed to generate diff. Skipping task"
                    return false
                }

                boolean hasNeededFiles = initializeDiffCoveragePlugin()

                if (!hasNeededFiles) {
                    project.logger.quiet "Skipping task '${task.path}' because there's nothing to do."
                }
                return hasNeededFiles
            }

            if (replaceDiffCoverage) {
                task.doLast {
                    runDiffCoverageCompliancePlugin()
                }
            }
        }
    }

    boolean initializeDiffCoveragePlugin() {
        Task task = project.tasks.getByName('diffCoverage')
        Object taskConfig = task.diffCoverageReport
        Object taskReportConfig = taskConfig.reportConfiguration
        Set<String> reportConfigNames = ['html', 'xml', 'csv', 'baseReportDir', 'fullCoverageReport']
        Map<String, Object> violationRuleOverrides = new HashMap<>(['minLines': 0, 'minBranches': 0, 'minInstructions': 0, 'failOnViolation': false])

        try {
            ConfigurableFileCollection srcFiles = project.files()
            ConfigurableFileCollection classFiles = project.files()
            ConfigurableFileCollection execFiles = project.files()

            Path diffPath = extension.diffOutputPath.get()
            getProjectPathMapFromDiff(diffPath)
                ?.keySet()
                ?.each { Project affectedProject ->
                    srcFiles.from affectedProject.sourceSets.main.allSource.srcDirs
                    classFiles.from affectedProject.sourceSets.main.output
                    execFiles.from affectedProject.files {
                        affectedProject.tasks.withType(JacocoReport)
                            .matching { !IGNORED_JACOCO_TASK_PATHS.contains(it.path) }
                            .collect { it.executionData }
                    }
                }

            boolean hasAllFiles = !(srcFiles.empty || classFiles.empty || execFiles.empty)
            if (!hasAllFiles) return hasAllFiles

            initialReportConfig.clear()
            extension.reports
                .properties
                .findAll { reportConfigNames.contains(it.key) }
                .each {
                    initialReportConfig.put(String.valueOf(it.key), it.value)
                }

            taskConfig.diffSource.tap {
                it.url = ''
                it.git.diffBase = ''
                it.file = diffPath.toString()
            }

            // Copy our reports configuration into embedded plugin's
            reportConfigNames.each {
                Object ourValue = extension.reports[it]
                while ((ourValue instanceof Provider)) {
                    ourValue = (ourValue as Provider).get()
                }
                taskReportConfig[it] = ourValue
            }
            // Insist on generating XML report since our analysis is impossible without it
            taskReportConfig.xml = true

            // For embedded plugin's violation rules, set everything to zero and turn off 'fail-on-violation'
            taskConfig.violationRules.tap { Object rules ->
                violationRuleOverrides.each { Map.Entry<String, Object> override ->
                    rules[override.key] = override.value
                }
            }

            taskConfig.srcDirs = srcFiles
            taskConfig.classesDirs = classFiles
            taskConfig.jacocoExecFiles = execFiles

            return true
        } catch (Exception ex) {
            project.logger.error("Failed to configure task 'diffCoverage'", ex)
        }
        return false
    }

    void evaluateViolationsAndRespond(DiffCoverageComplianceResult results) {
        if (extension.violationRules.failOnViolation) {
            Set<CoverageStat> failures = results.compliance.failed.sort { it.metric.ordinal() }
            if (!failures.empty) {
                String failedMetrics = failures.collect {
                    "${it.metric.thresholdKey}(required=${it.thresholdPercent(COMPACT)}, coverage=${it.coveragePercent(COMPACT)})"
                }.join(', ')
                String failMessage = "${this.class.simpleName} found ${grammar('{} violation', '{} violations').format(failures.size())}: ${failedMetrics}"

                project.logger.error(failMessage)
                throw new RuntimeException(failMessage)
            }
        }
    }

    void postTaskCleanup(DiffCoverageComplianceResult result) {
        if (!initialReportConfig.getOrDefault('xml', false) && result.reports.xmlPath != null) {
            project.delete result.reports.xmlPath
        }
    }

    private static <T extends Appendable> T appendLine(T self) {
        appendLine(self, null)
    }

    private static <T extends Appendable> T appendLine(T self, CharSequence line) {
        self.tap {
            append(line ?: '').append('\n')
        }
    }

    StringBuilder printCoverageResults(DiffCoverageComplianceResult results, StringBuilder buffer) {
        int projectCount = results.details.projects.size()
        Collection<DiffCoverageComplianceResult.FileDetail> allFiles = results.details.projects.collectMany { DiffCoverageComplianceResult.ProjectDetail pd -> pd.getAllFiles() }
        int fileCount = allFiles.size()
        String projectCountText = "${grammar(projectCount, '{} project', '{} projects')}"
        String fileCountText = "${grammar(fileCount, '{} file', '{} files')}"
        boolean connectMetricDetails = true

        buffer.tap {
            appendLine it, "\u256D${'\u2500' * 35}\u256E"
            appendLine it, "\u2502  DiffCoverage Compliance Summary  \u2502"
            appendLine it, "\u2570${'\u2500' * 35}\u256F"
            appendLine it, "Repository Details:"
            appendLine it, "    Current Branch:   \"${results.repository.currentBranch}\""
            appendLine it, "    Diff Base Branch: \"${results.repository.diffBaseBranch}\"  (${project.ext.diffBaseHash} from ${project.ext.diffBaseDateRelative})"
            appendLine it, ""
            appendLine it, "Coverage Details (${fileCountText} in ${projectCountText}):"

            printProjectDetails(results.details.projects, it, connectMetricDetails)

            appendLine it, ""
            appendLine it, "Overall Coverage Compliance:"
            printOverallCoverageCompliance(results, buffer, true)

            printReportDetails(results, buffer)

            appendLine it, "\u256D${'\u2500' * 35}\u256E"
            appendLine it, "\u2502  DiffCoverage Compliance Summary  \u2502"
            appendLine it, "\u2570${'\u2500' * 35}\u256F"
        }
    }

    private List printProjectDetails(Set<DiffCoverageComplianceResult.ProjectDetail> projectSet, StringBuilder it, boolean connectMetricDetails) {
        projectSet
            .sort { it.project.path }
            .eachWithIndex { DiffCoverageComplianceResult.ProjectDetail projectEntry, int projectIndex ->
                Project currentProject = projectEntry.project
                if (projectIndex > 0) {
                    appendLine it, ""
                }
                appendLine it, "    Project '${currentProject.path}'"
                Set<DiffCoverageComplianceResult.FileDetail> allFilesSet = projectEntry.allFiles
                allFilesSet
                    .sort { it.path }
                    .eachWithIndex { DiffCoverageComplianceResult.FileDetail fileDetails, int fileIndex ->
                        Set<CoverageStat> allMetrics = fileDetails.allCoverageMetrics
                        boolean isLastFile = !(fileIndex < (allFilesSet.size() - 1))
                        String vineProjectToFile = "${!isLastFile ? '\u251C' : '\u2514'}${'\u2500' * 2}"

                        if (fileIndex > 0) {
                            appendLine it, "    \u2502"
                        }
                        appendLine it, "    ${vineProjectToFile} File: ${fileDetails.path}"

                        // Get max size of "Counts" field
                        int maxCountsFieldSize = 0
                        int maxCoveredCountSize = 0
                        int maxMissingCountSize = 0
                        int maxTotalCountSize = 0
                        allMetrics.each { stat ->
                            int coveredLength = "${stat.covered}".length()
                            int missingLength = "${stat.missing}".length()
                            int totalLength = "${stat.total}".length()
                            int countsFieldLength = (Math.max(coveredLength, missingLength) + totalLength + 4)
                            if (maxCoveredCountSize < coveredLength) maxCoveredCountSize = coveredLength
                            if (maxMissingCountSize < missingLength) maxMissingCountSize = missingLength
                            if (maxTotalCountSize < totalLength) maxTotalCountSize = totalLength
                            if (maxCountsFieldSize < countsFieldLength) maxCountsFieldSize = countsFieldLength
                        }
                        Map<String, Integer> maxCountSize = new HashMap<>(['Covered': maxCoveredCountSize, 'Missing': maxMissingCountSize, 'Total': maxTotalCountSize, 'All': maxCountsFieldSize])

                        allMetrics.eachWithIndex { CoverageStat coverage, int metricIndex ->
                            boolean isLastMetric = !(metricIndex < (allMetrics.size() - 1))
                            String fieldMetricLabel = "${coverage.metric.label}:".padRight(13)
                            String fieldPercentage = "${coverage.coveragePercent}".padLeft(6)
                            String fieldCounts = "${String.valueOf(coverage.covered).padLeft(maxCountSize['Covered'])} of ${String.valueOf(coverage.total).padLeft(maxCountSize['Total'])}"
                            String vineProjectOverFile = "${!isLastFile ? '\u2502' : ' '}"
                            String vineFileToMetric = "${!isLastMetric ? '\u251C' : '\u2514'}${'\u2500' * 2}"

                            switch (coverage.metric) {
                            case LINE:
                                appendLine it, "    ${vineProjectOverFile}   ${vineFileToMetric} ${fieldMetricLabel.trim()}"
                                LineCoverageStat lineCoverage = (LineCoverageStat)coverage
                                List<Map.Entry<String, Set<Integer>>> lineDetails = lineCoverage.details.findAll { !it.value.empty }
                                lineDetails.eachWithIndex { Map.Entry<String, Set<Integer>> lineStatEntry, int lineStatIndex ->
                                    boolean isLastLineStat = !(lineStatIndex < (lineDetails.size() - 1))
                                    List<String> lineOutput = collectIntoRanges(lineStatEntry.value)
                                    String fieldListLabel = "${lineStatEntry.key}:".padRight(8)
                                    fieldPercentage = "${lineCoverage.percent(lineStatEntry.key)}".padLeft(6)
                                    fieldCounts = "${String.valueOf(coverage[lineStatEntry.key.toLowerCase()]).padLeft(maxCountSize[lineStatEntry.key])} of ${String.valueOf(coverage.total).padLeft(maxCountSize['Total'])}"
                                    String vineProjectOverMetric = "${!isLastFile ? '\u2502' : ' '}"
                                    String vineFileOverMetric = "${!isLastMetric ? '\u2502' : ' '}  "
                                    String vineMetricToDetail = "${connectMetricDetails ? (isLastLineStat ? '\u2514' : '\u251C') + ('\u2500' * 4) : '     '}"

                                    appendLine it, "    ${vineProjectOverMetric}   ${vineFileOverMetric} ${vineMetricToDetail} ${fieldListLabel} ${fieldPercentage}   ${fieldCounts}   ${grammar(lineOutput.size(), '', 'Line number', 'Line numbers')}: ${lineOutput.join(', ')}"
                                }
                                break
                            default:
                                appendLine it, "    ${vineProjectOverFile}   ${vineFileToMetric} ${fieldMetricLabel}  ${fieldPercentage}   ${fieldCounts}"
                            }
                        }
                    }
            }
    }

    StringBuilder printOverallCoverageCompliance(DiffCoverageComplianceResult results, StringBuilder buffer, boolean inTable) {
        String tableIndent = (' ' * 4)
        int maxCountsFieldSize = results.compliance.allCoverage.stream().mapToInt(stat -> "${stat.covered} of ${stat.total}".length()).max().orElse(8)
        int maxPolicyNoteSize = results.compliance.allCoverage.stream().mapToInt(stat -> (stat.hasViolationThreshold() ? "${stat.thresholdPercent.padLeft(6)} required" : "").length()).max().orElse(0)
        int maxCoveredWidth = 0
        int maxTotalWidth = 0
        results.compliance.allCoverage.each { CoverageStat coverage ->
            int coveredWidth = "${coverage.covered}".length()
            int totalWidth = "${coverage.total}".length()
            if (maxCoveredWidth < coveredWidth) maxCoveredWidth = coveredWidth
            if (maxTotalWidth < totalWidth) maxTotalWidth = totalWidth
        }

        if (inTable) {
            Map<String, Integer> columns = ['Compliance': 12, 'Coverage Metrics': 23, 'Covered': (maxCountsFieldSize + 2), 'Coverage Policy': (maxPolicyNoteSize + 2)]

            columns.each {
                int keyLength = (it.key.length() + 2)
                if (keyLength > it.value) {
                    it.value = keyLength
                }
            }

            buffer.tap {
                it.append "${tableIndent}\u250C"
                columns.eachWithIndex { String columnTitle, Integer columnWidth, int columnIndex ->
                    it.append('\u2500' * columnWidth)
                    if (columnIndex < (columns.size() - 1)) {
                        it.append('\u252C')
                    } else {
                        it.append('\u2510')
                    }
                }
                appendLine it

                it.append "${tableIndent}\u2502"
                columns.eachWithIndex { String columnTitle, Integer columnWidth, int columnIndex ->
                    it.append(" ${columnTitle.padRight(columnWidth - 2)} ")
                    it.append('\u2502')
                }
                appendLine it

                it.append "${tableIndent}\u251C"
                columns.eachWithIndex { String columnTitle, Integer columnWidth, int columnIndex ->
                    it.append('\u2500' * columnWidth)
                    if (columnIndex < (columns.size() - 1)) {
                        it.append('\u253C')
                    } else {
                        it.append('\u2524')
                    }
                }
                appendLine it

                results.compliance.allCoverage.each { CoverageStat coverage ->
                    String complianceResult = (coverage.canFail ? (coverage.passed ? "[  PASS  ]" : "[ FAILED ]") : "[   OK   ]")
                    String fieldMetricLabel = "${coverage.metric.label}:".padRight(13)
                    String fieldPercentage = "${coverage.coveragePercent}".padLeft(6)
                    String fieldCounts = "${String.valueOf(coverage.covered).padLeft(maxCoveredWidth)} of ${String.valueOf(coverage.total).padLeft(maxTotalWidth)}".padRight(columns['Covered'] - 2)
                    String fieldCoveragePolicyNote = (coverage.hasViolationThreshold() ? "${coverage.thresholdPercent.padLeft(6)} required" : '<none>').padRight(columns['Coverage Policy'] - 2)

                    appendLine it, "${tableIndent}\u2502 ${complianceResult} \u2502 ${fieldMetricLabel}  ${fieldPercentage} \u2502 ${fieldCounts} \u2502 ${fieldCoveragePolicyNote} \u2502"
                }

                it.append "${tableIndent}\u2514"
                columns.eachWithIndex { String columnTitle, Integer columnWidth, int columnIndex ->
                    it.append('\u2500' * columnWidth)
                    if (columnIndex < (columns.size() - 1)) {
                        it.append('\u2534')
                    } else {
                        it.append('\u2518')
                    }
                }
                appendLine it
            }
        } else {
            results.compliance.allCoverage.each { CoverageStat coverage ->
                String complianceResult = (coverage.canFail ? (coverage.passed ? "[ PASS ]" : "[FAILED]") : "[  OK  ]")
                String fieldMetricLabel = "${coverage.metric.label}:".padRight(13)
                String fieldPercentage = "${coverage.coveragePercent}".padLeft(6)
                String fieldCounts = "(${coverage.covered} of ${coverage.total})".padRight(maxCountsFieldSize)
                String fieldCoveragePolicyNote = (coverage.hasViolationThreshold() ? '   ' + "${coverage.thresholdPercent.padLeft(6)} required".padRight(maxPolicyNoteSize) : '')

                appendLine buffer, " ${complianceResult}   ${fieldMetricLabel}  ${fieldPercentage}   ${fieldCounts}${fieldCoveragePolicyNote}"
            }
        }
        return buffer
    }

    DiffCoverageComplianceResult evaluateCoverageCompliance(Task task, Map<Project, Set<Path>> projectPathMap, @ClosureParams(value = FromString.class, options = ['groovy.xml.slurpersupport.GPathResult, java.util.Map<org.gradle.api.Project, java.util.Set<com.witcraft.diffcoveragecompliance.SourcePathWithXmlNode>>, com.witcraft.diffcoveragecompliance.DiffCoverageComplianceResult']) Closure closure) {
        evaluateCoverageCompliance(task, projectPathMap, new DiffCoverageComplianceResult(), closure)
    }

    DiffCoverageComplianceResult evaluateCoverageCompliance(Task task, Map<Project, Set<Path>> projectPathMap, DiffCoverageComplianceResult result, @ClosureParams(value = FromString.class, options = ['groovy.xml.slurpersupport.GPathResult, java.util.Map<org.gradle.api.Project, java.util.Set<com.witcraft.diffcoveragecompliance.SourcePathWithXmlNode>>, com.witcraft.diffcoveragecompliance.DiffCoverageComplianceResult']) Closure closure) {
        Path projectPath = task.project.projectDir.toPath()
        Path baseReportPath = projectPath.resolve(extension.reports.baseReportDir.getOrElse(''))
        Path xmlReportPath = baseReportPath.resolve('diffCoverage').resolve('report.xml')

        String xmlContent = Files.readString(xmlReportPath)
        Pattern PATTERN_DOCTYPE = ~/<!DOCTYPE\s+\S+\s+(?:PUBLIC|SYSTEM)\s+(['"])+.*?\1+(?:\s+(['"])+.*?\2)?(?:\s+[^>]*)?>/
        xmlContent = xmlContent.replaceAll(PATTERN_DOCTYPE, '')

        XmlSlurper xmlSlurper = new XmlSlurper(false, true, true)

        GPathResult xmlReport = xmlSlurper.parseText(xmlContent)
        result.tap {
            closure.call(xmlReport, getProjectSourcePathMap(xmlReport, projectPathMap), it)
        }
    }

    void collectIntoCoverageResults(GPathResult xmlReport, Map<Project, Set<SourcePathWithXmlNode>> projectSourceMap, DiffCoverageComplianceResult results) {
        String diffBaseBranch = extension.diffBase.get()
        ViolationRules violationRules = extension.violationRules

        results.repository.currentBranch = project.ext.currentBranch
        results.repository.diffBaseBranch = diffBaseBranch

        // For each project...
        projectSourceMap
            .sort { it.key.path }
            .each { Project currentProject, Set<SourcePathWithXmlNode> projectSources ->
                // For each source file...
                projectSources.each { SourcePathWithXmlNode sourceWithXml ->
                    /// For each coverage metric...
                    processCoverageMetricsUsing(sourceWithXml.node, results) { GPathResult xmlContainer, GPathResult xmlCounter ->
                        CoverageMetric metric = CoverageMetric.from(((xmlCounter['@type'] as GPathResult).text()).toUpperCase())
                        CoverageStat coverage
                        if (metric == LINE) {
                            coverage = LineCoverageStat.from(xmlCounter, violationRules)
                                .addLines(xmlContainer.line as GPathResult)
                        } else {
                            coverage = CoverageStat.from(xmlCounter, violationRules)
                        }
                        results.details.project(currentProject).file(sourceWithXml.path).coverage(metric).from(coverage)
                    }
                }
            }

        processCoverageMetricsUsing(xmlReport, results) { GPathResult xmlCounter ->
            // For each aggregate metric
            CoverageStat stat = CoverageStat.from(xmlCounter, violationRules)
            results.compliance.coverage(stat.metric).from stat
        }

        Path baseReportPath = project.projectDir.toPath().resolve(extension.reports.baseReportDir.getOrElse(''))
        Path xmlReportPath = baseReportPath.resolve('diffCoverage').resolve('report.xml')
        Path csvReportPath = baseReportPath.resolve('diffCoverage').resolve('report.csv')
        Path htmlReportPath = xmlReportPath.resolveSibling('html/index.html')

        results.reports.xmlPath = xmlReportPath.toString()
        results.reports.csvPath = csvReportPath.toString()
        results.reports.htmlPath = htmlReportPath.toString()
    }

    Map<Project, Set<SourcePathWithXmlNode>> getProjectSourcePathMap(GPathResult xmlReport, Map<Project, Set<Path>> projectPathMap) {
        Task task = project.tasks.getByName('diffCoverage')

        Set<Path> pathsFoundInReport = new HashSet<>()
        Set<Path> pathsNotInReport = new HashSet<>()
        Map<Project, Set<SourcePathWithXmlNode>> projectSourceMap = [:]
        xmlReport.package.sourcefile.each { GPathResult xmlSourcefile ->
            String packagePath = "${xmlSourcefile.parent()['@name']}"
            String sourceName = "${xmlSourcefile['@name']}"
            String sourcePathInProject = "${packagePath}/${sourceName}"

            Project matchedProject = null
            Path matchedPath = null
            projectPathMap.each { Map.Entry<Project, Set<Path>> entry ->
                entry.key.sourceSets.main.allJava.srcDirTrees.find { DirectoryTree srcRoot ->
                    entry.value.find { Path path ->
                        if (path == Paths.get("${srcRoot.dir}", sourcePathInProject)) {
                            matchedProject = entry.key
                            matchedPath = path
                            return true
                        }
                        return false
                    } != null
                }
            }
            if (matchedProject != null && matchedPath != null) {
                pathsFoundInReport.add(matchedPath)
                projectSourceMap
                    .computeIfAbsent(matchedProject, key -> new LinkedHashSet<SourcePathWithXmlNode>())
                    .add(new SourcePathWithXmlNode(matchedPath, xmlSourcefile))
            } else {
                task.logger.warn "Could not match a project with the source file in report: ${sourcePathInProject}"
                pathsNotInReport.add(Paths.get(sourcePathInProject))
            }
        }
        return projectSourceMap
    }

    //void printCoverageDetails(GPathResult xmlReport, Map<Project, Set<SourcePathWithXmlNode>> projectSourceMap, DiffCoverageComplianceResult results) {
    //    Task task = project.tasks.getByName('diffCoverage')
    //
    //    projectSourceMap
    //        .sort { it.key.path }
    //        .eachWithIndex { Project currentProject, Set<SourcePathWithXmlNode> projectSources, int projectIndex ->
    //            boolean isLastProject = !(projectIndex < (projectSourceMap.size() - 1))
    //            task.logger.tap {
    //                if (projectIndex > 0) {
    //                    quiet ""
    //                }
    //                quiet "    Project '${currentProject.path}'"
    //                projectSources.eachWithIndex { SourcePathWithXmlNode sourceWithXml, int index ->
    //                    boolean isLastFile = !(index < (projectSources.size() - 1))
    //                    //quiet "        File: ${sourceWithXml.path}"
    //                    quiet "    ${!isLastFile ? '\u251C' : '\u2514'}${'\u2500' * 2} File: ${sourceWithXml.path}"
    //                    processCoverageMetricsUsing(sourceWithXml.node, results) { GPathResult xmlContainer, GPathResult xmlCounter, DiffCoverageComplianceResult result, boolean isLastMetric ->
    //                        printCoverageMetricForXmlCounter(sourceWithXml, xmlCounter, results, isLastProject, isLastFile, isLastMetric)
    //                    }
    //                }
    //            }
    //        }
    //}

    //void printCoverageMetricForXmlCounter(SourcePathWithXmlNode sourcePathWithXmlNode, GPathResult xmlCounter, DiffCoverageComplianceResult results, boolean isLastProject, boolean isLastFile, boolean isLastMetric) {
    //    Task task = project.tasks.getByName('diffCoverage')
    //    ViolationRules violationRules = extension.violationRules
    //    CoverageMetric metric = CoverageMetric.from((xmlCounter['@type'] as GPathResult).text().toUpperCase())
    //    double violationThreshold = violationRules.thresholdFor(metric)
    //    CoverageStat coverage = new CoverageStat(xmlCounter, violationThreshold)
    //    String fieldMetricLabel = "${coverage.metric.label}:".padRight(13)
    //    String fieldPercentage = "${coverage.coveragePercent}".padLeft(6)
    //    String fieldCounts = "${coverage.covered} of ${coverage.total}"
    //    task.logger.tap {
    //        //quiet "            ${fieldMetricLabel}  ${fieldPercentage}   ${fieldCounts}"
    //        quiet "    ${!isLastFile ? '\u2502' : ' '}   ${!isLastMetric ? '\u251C' : '\u2514'}${'\u2500' * 2} ${fieldMetricLabel}  ${fieldPercentage}   ${fieldCounts}"
    //        if (metric == LINE) {
    //            printLineCoverageDetails(sourcePathWithXmlNode, results, isLastProject, isLastFile, isLastMetric)
    //        }
    //    }
    //}

    //void printLineCoverageDetails(SourcePathWithXmlNode sourcePathWithXmlNode, DiffCoverageComplianceResult results, boolean isLastProject, boolean isLastFile, boolean isLastMetric) {
    //    Task task = project.tasks.getByName('diffCoverage')
    //    GPathResult xmlSource = sourcePathWithXmlNode.node
    //
    //    CoverageStat coverage = new CoverageStat(LINE, xmlSource.counter as GPathResult)
    //    Map<String, String> coveragePercentMap = ['Covered': coverage.coveragePercent, 'Missing': coverage.missingPercent]
    //
    //    Map<String, List<Integer>> lineMap = ['Covered': [], 'Missing': []]
    //    xmlSource.line.each { GPathResult line ->
    //        int lineNumber = Integer.parseInt((line['@nr'] as GPathResult).text())
    //        int coveredInstructions = Integer.parseInt((line['@ci'] as GPathResult).text())
    //        int coveredBranches = Integer.parseInt((line['@cb'] as GPathResult).text())
    //        if ((coveredInstructions + coveredBranches) > 0) {
    //            lineMap['Covered'].add(lineNumber)
    //        } else {
    //            lineMap['Missing'].add(lineNumber)
    //        }
    //    }
    //
    //    lineMap
    //        .findAll { !it.value.empty }
    //        .each { String listLabel, List<Integer> lineNumberList ->
    //            List<String> lineOutput = collectIntoRanges(lineNumberList)
    //            String fieldListLabel = "${listLabel}:".padRight(8).padLeft(11)
    //            String fieldPercentage = "${coveragePercentMap[listLabel]}".padLeft(6)
    //            //task.logger.quiet "               ${fieldListLabel} ${fieldPercentage}   ${lineOutput.join(', ')}"
    //            task.logger.quiet "    ${!isLastFile ? '\u2502' : ' '}   ${!isLastMetric ? '\u2502' : '\u2514'}      ${fieldListLabel} ${fieldPercentage}   ${lineOutput.join(', ')}"
    //        }
    //}

    //Map<Project, Set<Path>> collectByProject(Map<Project, Set<Path>> projectPathMap, Path gitRepoPath, Collection<Path> filesAffectedByDiff) {
    //    if (projectPathMap == null) {
    //        projectPathMap = new LinkedHashMap<>()
    //    }
    //
    //    filesAffectedByDiff.each { Path path ->
    //        List<Project> projectsContainingFile = project.allprojects
    //            .findResults { (path.startsWith(it.projectDir.toPath()) ? it : null) }
    //            .sort { Project a, Project b -> b.depthCompare(a) }
    //
    //        Path repoRelativePath = gitRepoPath.relativize(path)
    //        if (!projectsContainingFile.empty) {
    //            Project leafProject = projectsContainingFile.first()
    //            if (projectsContainingFile.size() > 1) {
    //                project.logger.debug "File \"${repoRelativePath}\" is contained within:"
    //                project.logger.debug "    Leaf Project: ${leafProject.path}"
    //                project.logger.debug "    Projects: ${projectsContainingFile.path}"
    //            } else {
    //                project.logger.debug "File \"${repoRelativePath}\" is contained within project '${leafProject.path}'"
    //            }
    //            projectPathMap.computeIfAbsent(leafProject, { new HashSet<>() }).add(path)
    //        } else {
    //            project.logger.warn "Failed to find project containing \"${repoRelativePath}\"!"
    //        }
    //    }
    //
    //    return projectPathMap
    //}

    //void printCoverageComplianceSummary(GPathResult xmlReport, Map<Project, Set<SourcePathWithXmlNode>> projectSourceMap, DiffCoverageComplianceResult results) {
    //    String diffBaseBranch = extension.diffBase.get()
    //    Set<SourcePathWithXmlNode> allSourceFiles = projectSourceMap.values().collectMany(new HashSet<SourcePathWithXmlNode>()) { it }
    //    int projectCount = projectSourceMap.size()
    //    int fileCount = allSourceFiles.size()
    //    String projectCountText = "${projectCount} project${projectCount == 1 ? '' : 's'}"
    //    String fileCountText = "${fileCount} file${fileCount == 1 ? '' : 's'}"
    //
    //    project.logger.tap {
    //        quiet "\u256D${'\u2500' * 35}\u256E"
    //        quiet "\u2502  DiffCoverage Compliance Summary  \u2502"
    //        quiet "\u2570${'\u2500' * 35}\u256F"
    //        quiet "Repository Details:"
    //        quiet "    Current Branch:   \"${project.ext.currentBranch}\""
    //        quiet "    Diff Base Branch: \"${diffBaseBranch}\"  (${project.ext.diffBaseHash} from ${project.ext.diffBaseDateRelative})"
    //        quiet ""
    //        quiet "Coverage Details (${fileCountText} in ${projectCountText}):"
    //        printCoverageDetails(xmlReport, projectSourceMap, results)
    //        quiet ""
    //        quiet "Overall Coverage Compliance:"
    //        printOverallCoverageCompliance(xmlReport, projectSourceMap, results)
    //        printReportDetails(results)
    //        quiet "\u256D${'\u2500' * 35}\u256E"
    //        quiet "\u2502  DiffCoverage Compliance Summary  \u2502"
    //        quiet "\u2570${'\u2500' * 35}\u256F"
    //    }
    //}

    //void printOverallCoverageCompliance(GPathResult xmlReport, Map<Project, Set<SourcePathWithXmlNode>> projectPathMap, DiffCoverageComplianceResult results) {
    //    Task task = project.tasks.getByName('diffCoverage')
    //    ViolationRules violationRules = extension.violationRules
    //
    //    int maxCountsFieldSize = getMaxLengthFor(xmlReport.counter as Iterable<GPathResult>) {
    //        CoverageStat coverage = new CoverageStat(it)
    //        "(${coverage.covered} of ${coverage.total})"
    //    }
    //
    //    processCoverageMetricsUsing(xmlReport, results) { GPathResult xmlCounter ->
    //        CoverageMetric metric = CoverageMetric.from((xmlCounter['@type'] as GPathResult).text())
    //        double violationThreshold = violationRules.thresholdFor(metric)
    //        CoverageStat coverage = new CoverageStat(xmlCounter, violationThreshold)
    //
    //        String result = (coverage.canFail ? (coverage.passed ? "[ PASS ]" : "[FAILED]") : "[  OK  ]")
    //        String fieldMetricLabel = "${coverage.metric.label}:".padRight(13)
    //        String fieldPercentage = "${coverage.coveragePercent}".padLeft(6)
    //        String fieldCounts = "(${coverage.covered} of ${coverage.total})".padRight(maxCountsFieldSize)
    //        String fieldCoveragePolicyNote = (coverage.hasViolationThreshold() ? "   (Coverage Policy is ${coverage.thresholdPercent})" : '')
    //
    //        task.logger.quiet " ${result}   ${fieldMetricLabel}  ${fieldPercentage}   ${fieldCounts}${fieldCoveragePolicyNote}"
    //    }
    //}

    void processCoverageMetricsUsing(GPathResult xmlContainer, DiffCoverageComplianceResult results, @ClosureParams(value = FromString.class, options = ["groovy.xml.slurpersupport.GPathResult", "groovy.xml.slurpersupport.GPathResult, groovy.xml.slurpersupport.GPathResult", "groovy.xml.slurpersupport.GPathResult, groovy.xml.slurpersupport.GPathResult, com.witcraft.diffcoveragecompliance.DiffCoverageComplianceResult", "groovy.xml.slurpersupport.GPathResult, groovy.xml.slurpersupport.GPathResult, com.witcraft.diffcoveragecompliance.DiffCoverageComplianceResult, Boolean"]) Closure handler) {
        Closure forwardToHandler = { GPathResult xmlCounter, boolean isLastMetric ->
            if (handler.maximumNumberOfParameters == 4) {
                handler.call(xmlContainer, xmlCounter, results, isLastMetric)
            } else if (handler.maximumNumberOfParameters == 3) {
                handler.call(xmlContainer, xmlCounter, results)
            } else if (handler.maximumNumberOfParameters == 2) {
                handler.call(xmlContainer, xmlCounter)
            } else {
                handler.call(xmlCounter)
            }
        }

        if (displayOrderUsesEnum) {
            List<CoverageMetric> metricsInReport = []
            CoverageMetric.values().each { CoverageMetric metric ->
                GPathResult xmlCounter = (xmlContainer.counter as GPathResult).find { CoverageMetric.from(((it['@type'] as GPathResult).text()).toUpperCase()) == metric }
                if (xmlCounter != null && xmlCounter.size() > 0) {
                    metricsInReport.add(metric)
                } else {
                    project.logger.debug "No metric for \"${metric.name()}\" found in report"
                }
            }

            int metricCount = metricsInReport.size()
            metricsInReport.eachWithIndex { CoverageMetric metric, int index ->
                GPathResult xmlCounter = (xmlContainer.counter as GPathResult).find { CoverageMetric.from(((it['@type'] as GPathResult).text()).toUpperCase()) == metric }
                boolean isLastMetric = !(index < (metricCount - 1))
                forwardToHandler.call(xmlCounter, isLastMetric)
            }
        } else {
            int metricCount = xmlContainer.counter.size()
            xmlContainer.counter.eachWithIndex { GPathResult xmlCounter, int index ->
                boolean isLastMetric = !(index < (metricCount - 1))
                forwardToHandler.call(xmlCounter, isLastMetric)
            }
        }
    }

    @SuppressWarnings('unused')
    static <T> int getMaxLengthFor(Iterable<T> collection, @ClosureParams(value = FromString.class, options = ['T']) Closure<CharSequence> fieldResolver) {
        collection.toList()
            .stream()
            .filter(item -> !(item instanceof GPathResult) || (item as GPathResult).size() > 0)
            .map(item -> fieldResolver.call(item))
            .mapToInt(item -> (item != null ? item.length() : 0))
            .max().orElse(0)
    }

    StringBuilder printReportDetails(DiffCoverageComplianceResult results, StringBuilder buffer) {
        Reports reportsConfig = extension.reports
        Path xmlReportPath = Paths.get(results.reports.xmlPath)
        Path csvReportPath = Paths.get(results.reports.csvPath)
        Path htmlReportPath = Paths.get(results.reports.htmlPath)

        //boolean htmlReportRequested = Boolean.valueOf(String.valueOf(initialReportConfig.get('html')))
        //boolean csvReportRequested = Boolean.valueOf(String.valueOf(initialReportConfig.get('csv')))
        //boolean xmlReportRequested = Boolean.valueOf(String.valueOf(initialReportConfig.get('xml')))

        boolean htmlReportNeeded = Boolean.valueOf(reportsConfig.html as boolean)
        boolean csvReportNeeded = Boolean.valueOf(reportsConfig.csv as boolean)
        boolean xmlReportNeeded = Boolean.valueOf(reportsConfig.xml as boolean)

        boolean hasHtmlReport = (htmlReportNeeded && Files.exists(htmlReportPath))
        boolean hasCsvReport = (csvReportNeeded && Files.exists(csvReportPath))
        boolean hasXmlReport = (xmlReportNeeded && Files.exists(xmlReportPath))

        //boolean hasAnyReport = (hasHtmlReport || hasCsvReport || hasXmlReport)

        //LogLevel logLevelForReports = (hasAnyReport ? LogLevel.QUIET : LogLevel.INFO)
        //LogLevel logLevelForHtml = (hasHtmlReport ? LogLevel.QUIET : LogLevel.INFO)
        //LogLevel logLevelForCsv = (hasCsvReport ? LogLevel.QUIET : LogLevel.INFO)
        //LogLevel logLevelForXml = (hasXmlReport ? LogLevel.QUIET : LogLevel.INFO)

        buffer.tap {
            String htmlPath = (hasHtmlReport ? "${htmlReportPath.toUri()}" : "Not generated")
            String csvPath = (hasCsvReport ? "${csvReportPath.toUri()}" : "Not generated")
            String xmlPath = (hasXmlReport ? "${xmlReportPath.toUri()}" : "Not generated")

            appendLine it
            appendLine it, "Reports:"
            appendLine it, "    HTML: ${htmlPath}"
            appendLine it, "    CSV:  ${csvPath}"
            appendLine it, "    XML:  ${xmlPath}"
        }
    }

    private Set<Project> getAffectedProjectsFromPaths(Collection<Path> paths) {
        paths?.findResults { return getProjectFromPath(it) }?.toSet()
    }

    private Project getProjectFromPath(Path path) {
        getProjectsFromPath(path).findResult { it }
    }

    private Set<Project> getProjectsFromPath(Path path) {
        project.allprojects
            .findAll { path.startsWith(it.projectDir.toPath()) }
            .sort { Project a, Project b -> b.depthCompare(a) }
            ?.toSet()
    }

    private Map<Project, Set<Path>> getProjectPathMap(Collection<Path> paths) {
        return new HashMap<Project, Set<Path>>().tap { HashMap<Project, Set<Path>> result ->
            paths?.each { Path path ->
                result.computeIfAbsent(getProjectFromPath(path), { new LinkedHashSet<>() }).add(path)
            }
        }
    }

    private List<Tuple<String>> getDiffNameStatus(String diffBase) {
        final Pattern PATTERN_GIT_STATUS_NAME = ~/^(?<status>\S)\s+(?<name>.+)$/
        Stream<String> gitStream = doGitStream(project, 'diff', '--name-status', '--diff-filter=MARC', diffBase, '--')
        if (gitStream != null) {
            return gitStream
                .map(line -> {
                    Matcher m = PATTERN_GIT_STATUS_NAME.matcher(line)
                    (m.matches() ? Tuple.tuple(m.group("name"), m.group("status")) : null)
                })
                .collect()
        }
        return Collections.emptyList()
    }

    private boolean generateWorkingTreeDiff(Path diffOutFile, String diffBase) {
        Path pathToGit = which(GIT_COMMAND_NAME)

        project.mkdir diffOutFile.parent
        if (Files.exists(diffOutFile)) {
            project.delete diffOutFile
        }

        List<String> gitArgs = ['diff', '--patch', '--patience', "--output=${diffOutFile}", '--merge-base', diffBase, '--']
        try {
            ExecResult execResult = project.exec { ExecSpec e ->
                e.executable = pathToGit
                e.args = gitArgs
                e.ignoreExitValue = true
            }
            return (execResult.exitValue == 0)
        } catch (Exception ex) {
            project.logger.error("Failed to execute '${GIT_COMMAND_NAME}${gitArgs.size() > 0 ? ' ' + gitArgs.join(' ') : ''}'", ex)
        }
        return false
    }

    @SuppressWarnings('unused')
    private Set<Path> getWorkingTreeDiff(Path gitRepoPath, String diffBase) {
        return doGitStream(project, 'diff', '-p', '--merge-base', diffBase, '--')
            ?.filter { it.startsWith("+++ ") }
            ?.map { matchFirstPattern(it, PATTERN_GIT_DIFF_NEW_FILE) }
            ?.filter { it != null }
            ?.map { gitRepoPath.resolve(it.group('path')) }
            ?.filter { Files.exists(it) }
            ?.collect(Collectors.toSet())
    }

    private List<Path> getAffectedFilesFromDiff(Path diffFile) {
        if (Files.isReadable(diffFile)) {
            Path gitRepoPath = findGitRepoPath(project)
            try (Stream<String> diffLinesStream = Files.lines(diffFile)) {
                List<Path> pathsAffectedByDiff = diffLinesStream
                    .filter { it.startsWith('+++ ') }
                    .map { matchFirstPattern(it, PATTERN_GIT_DIFF_NEW_FILE) }
                    .filter { it != null }
                    .map { gitRepoPath.resolve(it.group('path')) }
                    .filter { Files.exists(it) }
                    .collect()
                return pathsAffectedByDiff
            } catch (IOException ex) {
                project.logger.error("Failed to read diff file!", ex)
            }
        }
        return null
    }

    static String doGit(Project project, String... args) {
        Stream<String> gitStream = doGitStream(project, args)
        return gitStream?.toList()?.join('\n')?.trim()
    }

    static Stream<String> doGitStream(Project project, String... args) {
        Path gitPath = which('git')

        List<String> gitArgs = Arrays.asList(args)
        try {
            return new ByteArrayOutputStream().withStream { ByteArrayOutputStream os ->
                ExecResult execResult = project.exec { ExecSpec e ->
                    e.executable = gitPath
                    e.args = gitArgs
                    e.standardOutput = os
                    e.ignoreExitValue = true
                }
                if (execResult.exitValue == 0) {
                    return os.toString().lines().map(String::trim)
                }
                project.logger.debug("Non-zero exit value (${execResult.exitValue}) for command: ${gitPath}${gitArgs.size() > 0 ? ' ' + gitArgs.join(' ') : ''}")
                return null
            }
        } catch (ExecException ex) {
            System.err.println("Failed to execute '${GIT_COMMAND_NAME}${gitArgs.size() > 0 ? ' ' + gitArgs.join(' ') : ''}'!")
            ex.printStackTrace()
        }
        return null
    }

    private static Matcher matchFirstPattern(String input, Pattern... patterns) {
        return patterns
            .collect { it.matcher(input) }
            .find { it.matches() }
    }

    @SuppressWarnings('unused')
    private static Matcher findFirstPattern(String input, Pattern... patterns) {
        return patterns
            .collect { it.matcher(input) }
            .find { it.find() }
    }

    private static List<String> collectIntoRanges(Collection<Integer> lineNumbers) {
        if (lineNumbers.empty) {
            return Collections.emptyList()
        }
        lineNumbers = lineNumbers.sort()
        List<String> result = []
        int startLine
        int endLine
        int previousLine = endLine = startLine = lineNumbers[0]
        Closure<Void> addLineRangeText = { List<String> output, int start, int end ->
            int size = (end - start)
            if (size > 0) {
                output.add "${start}-${end}"
            } else {
                output.add "${start}"
            }
        }

        for (int i = 1; i < lineNumbers.size(); i++) {
            int currentLine = lineNumbers[i]
            if ((previousLine + 1) != currentLine) {
                addLineRangeText.call(result, startLine, endLine)
                startLine = currentLine
            }
            previousLine = endLine = currentLine
        }
        addLineRangeText.call(result, startLine, endLine)

        return result
    }

    static final Path findGitRepoPath(Project project) {
        Path tempPath = project.projectDir.toPath()
        //noinspection GroovyUnusedAssignment
        do {
            Path gitDir = tempPath.resolve('.git')
            if (Files.isDirectory(gitDir)) {
                return gitDir.parent
            }
        } while (Objects.nonNull(tempPath = tempPath.parent))
        return null
    }

    static final Path which(String name) {
        String envPath
        String envPathExt = ''
        String PATH_SEPARATOR = System.getProperty('path.separator', ';')
        Map<String, String> env = System.getenv()
        env.keySet()
            .findAll { String key ->
                ("Path".equalsIgnoreCase(key) || "PathExt".equalsIgnoreCase(key))
            }
            .each { String key ->
                if ("Path".equalsIgnoreCase(key)) {
                    envPath = env.get(key).trim().replaceAll("\\s*${Pattern.quote(PATH_SEPARATOR)}\\s*", PATH_SEPARATOR)
                } else if ("PathExt".equalsIgnoreCase(key)) {
                    envPathExt = env.get(key).trim().toLowerCase().replaceAll('\\s*;\\s*', ';')
                }
            }

        if (envPathExt == null || envPathExt.empty) {
            envPathExt = '.exe;.sh'
        }

        final List<String> namesToTry = [name]
        envPathExt.tokenize(';').each {
            namesToTry.add "${name}${it}"
        }

        if (envPath != null && !envPath.empty) {
            return envPath.tokenize(PATH_SEPARATOR).findResult { String pathToTry ->
                namesToTry.findResult { String nameToTry ->
                    Path path = Paths.get(pathToTry, nameToTry)
                    return (Files.isExecutable(path) ? path : null)
                }
            }
        }
        return null
    }

}






