package com.witcraft.diffcoveragecompliance

import groovy.xml.slurpersupport.GPathResult
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested

import javax.inject.Inject
import java.nio.file.Path
import java.nio.file.Paths

import static com.witcraft.diffcoveragecompliance.CoverageMetric.INSTRUCTION
import static com.witcraft.diffcoveragecompliance.CoverageMetric.BRANCH
import static com.witcraft.diffcoveragecompliance.CoverageMetric.LINE
import static com.witcraft.diffcoveragecompliance.CoverageMetric.COMPLEXITY
import static com.witcraft.diffcoveragecompliance.CoverageMetric.METHOD
import static com.witcraft.diffcoveragecompliance.CoverageMetric.CLASS
import static com.witcraft.diffcoveragecompliance.DiffCoverageCompliancePlugin.DIFF_COVERAGE_COMPLIANCE_NAME
import static com.witcraft.diffcoveragecompliance.DiffCoverageCompliancePlugin.resolveProvided

abstract class DiffCoverageCompliancePluginExtension {
    public static final String DIFF_BASE_PROPERTY_KEY = "${DIFF_COVERAGE_COMPLIANCE_NAME}.diffBase"

    @Inject protected abstract ObjectFactory getObjectFactory()

    @Input abstract Property<String> getReportName()

    @Input abstract Property<Boolean> getColor()

    @Input abstract Property<String> getDiffBase()

    @InputDirectory abstract Property<Path> getDiffOutputPath()

    @Nested
    ViolationRules violationRules

    @Nested
    Reports reports

    DiffCoverageCompliancePluginExtension(Project project) {
        setupPropertyConvention(project, 'reportName', project.projectDir.name)
        setupPropertyConvention(project, 'color', true)
        setupPropertyConvention(project, 'diffBase', project.provider {
            project.logger.debug "Checking for project property '${DIFF_BASE_PROPERTY_KEY}'"
            String.valueOf(project.properties.getOrDefault(DIFF_BASE_PROPERTY_KEY, '')).trim().tap {
                if (!it.empty) {
                    project.logger.debug "Property '${DIFF_BASE_PROPERTY_KEY}' is '${it}'"
                } else {
                    project.logger.debug "Property '${DIFF_BASE_PROPERTY_KEY}' is empty"
                }
            }
        })
        setupPropertyConvention(project, 'diffOutputPath', Paths.get("${project.buildDir}", DIFF_COVERAGE_COMPLIANCE_NAME, 'patch.diff'))
        violationRules = objectFactory.newInstance(ViolationRules.class)
        reports = objectFactory.newInstance(Reports.class, project)
    }

    private <T> void setupPropertyConvention(Project project, String propertyName, T defaultValue) {
        Object member = this[propertyName]
        if (member instanceof Property<T>) {
            (member as Property<T>).convention project.provider {
                project.logger.debug "Property '${propertyName}' is unspecified. Default value '${defaultValue}' will be used."
                return defaultValue
            }
        }
    }

    private <T> void setupPropertyConvention(Project project, String propertyName, Provider<T> defaultProvider) {
        Object member = this[propertyName]
        if (member instanceof Property<T>) {
            (member as Property<T>).convention project.provider {
                T result = defaultProvider.orNull
                project.logger.debug "Property '${propertyName}' is unspecified. Default value '${result}' will be used."
                return result
            }
        }
    }

    ViolationRules violationRules(Action<ViolationRules> action) {
        violationRules.tap {
            action.execute(violationRules)
        }
    }

    Reports reports(Action<Reports> action) {
        reports.tap {
            action.execute(reports)
        }
    }
}

class Reports {

    private static final List<String> fieldsToString = ['baseReportDir', 'fullCoverageReport', 'csv', 'html', 'xml']

    @Input
    Property<String> baseReportDir
    @Input
    boolean csv
    @Input
    boolean html
    @Input
    boolean xml
    @Input
    boolean fullCoverageReport

    @Inject
    Reports(ObjectFactory objectFactory, Project project) {
        baseReportDir = objectFactory.property(String.class)
            .convention project.provider {
            Paths.get('build', 'reports', 'jacoco').toString().tap {
                project.logger.debug "Using default for 'baseReportDir': ${it}"
            }
        }
    }

    Reports csv(boolean value) { this.tap { this.@csv = value } }

    Reports html(boolean value) { this.tap { this.@html = value } }

    Reports xml(boolean value) { this.tap { this.@xml = value } }

    Reports fullCoverageReport(boolean value) { this.tap { this.@fullCoverageReport = value } }

    Reports baseReportDir(String baseReportDir) { setBaseReportDir(baseReportDir) }

    Reports baseReportDir(Path baseReportDir) { setBaseReportDir(baseReportDir) }

    Reports setBaseReportDir(String baseReportDir) { this.tap { it.@baseReportDir.set baseReportDir } }

    Reports setBaseReportDir(Path baseReportDir) { this.tap { it.@baseReportDir.set baseReportDir.toString() } }

    @Override
    String toString() {
        new StringBuilder('Reports(').tap { StringBuilder result ->
            fieldsToString.eachWithIndex { String field, int i ->
                if (i > 0) result.append(', ')
                result.append(field).append('=').append(resolveProvided(this.getProperty(field)))
            }
        }.append(')').toString()
    }
}

class ViolationRules {

    private static final List<String> fieldsToString = ['minInstructions', 'minBranches', 'minLines', 'minComplexity', 'minMethods', 'minClasses', 'failOnViolation']

    private Map<CoverageMetric, Double> metricRules
    @Input
    boolean failOnViolation

    ViolationRules() {
        metricRules = [
            (LINE)       : 0d,
            (BRANCH)     : 0d,
            (INSTRUCTION): 0d,
            (COMPLEXITY) : 0d,
            (METHOD)     : 0d,
            (CLASS)      : 0d,
        ]
    }

    double thresholdFor(GPathResult xmlNode) {
        getThresholdFor(CoverageMetric.from((xmlNode['@type'] as GPathResult).text() as String))
    }

    double thresholdFor(CoverageMetric metric) { getThresholdFor(metric) }

    double getThresholdFor(CoverageMetric metric) { metricRules.getOrDefault(metric, 0d) }

    @Input double getMinLines() { thresholdFor(LINE) }

    @Input ViolationRules setMinLines(double minLines) { metricRules.put(LINE, minLines); return this }

    @Input double getMinBranches() { thresholdFor(BRANCH) }

    @Input ViolationRules setMinBranches(double minBranches) { metricRules.put(BRANCH, minBranches); return this }

    @Input double getMinInstructions() { thresholdFor(INSTRUCTION) }

    @Input ViolationRules setMinInstructions(double minInstructions) {
        metricRules.put(INSTRUCTION, minInstructions); return this
    }

    @Input double getMinComplexity() { thresholdFor(COMPLEXITY) }

    @Input ViolationRules setMinComplexity(double minComplexity) {
        metricRules.put(COMPLEXITY, minComplexity); return this
    }

    @Input double getMinMethods() { thresholdFor(METHOD) }

    @Input ViolationRules setMinMethods(double minMethods) { metricRules.put(METHOD, minMethods); return this }

    @Input double getMinClasses() { thresholdFor(CLASS) }

    @Input ViolationRules setMinClasses(double minClasses) { metricRules.put(CLASS, minClasses); return this }

    @Input boolean isFailOnViolation() { failOnViolation }

    @Input ViolationRules setFailOnViolation(boolean failOnViolation) {
        this.@failOnViolation = failOnViolation; return this
    }

    @Override
    String toString() {
        new StringBuilder('ViolationRules(').tap { StringBuilder result ->
            fieldsToString.eachWithIndex { String field, int i ->
                if (i > 0) result.append(', ')
                result.append(field).append('=').append(resolveProvided(this.getProperty(field)))
            }
        }.append(')').toString()
    }
}