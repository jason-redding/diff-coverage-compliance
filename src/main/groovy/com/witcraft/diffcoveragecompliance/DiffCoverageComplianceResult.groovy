package com.witcraft.diffcoveragecompliance

import org.gradle.api.Project

import java.nio.file.Path

class DiffCoverageComplianceResult {
    private Repository repository
    private Details details
    private Compliance compliance
    private Reports reports

    DiffCoverageComplianceResult() {
        repository = new Repository()
        details = new Details()
        compliance = new Compliance()
        reports = new Reports()
    }

    Repository getRepository() { repository }

    Details getDetails() { details }

    Compliance getCompliance() { compliance }

    Reports getReports() { reports }

    @Override
    String toString() {
        "${this.class.simpleName}(repository=${repository}, details=${details}, compliance=${compliance}, reports=${reports})"
    }

    class Repository {
        String currentBranch
        String diffBaseBranch

        @Override
        String toString() {
            "${this.class.simpleName}(currentBranch=${currentBranch}, diffBaseBranch=${diffBaseBranch})"
        }
    }

    class Details {
        private Map<Project, ProjectDetail> projects

        Details() { projects = [:] }

        ProjectDetail project(Project project) { projects.computeIfAbsent(project, p -> new ProjectDetail(p)) }

        ProjectDetail getProject(Project project) { projects.get(project) }

        Set<ProjectDetail> getProjects() { projects.values() as Set<ProjectDetail> }

        Details setProjects(Set<ProjectDetail> projects) { this.tap { it.projects = projects } }

        @Override
        String toString() {
            "Details(${this.getProjects()})"
        }
    }

    class ProjectDetail {
        private Project project
        private Map<Path, FileDetail> files

        ProjectDetail() { this(null) }

        ProjectDetail(Project project) { this.project = project; files = [:] }

        Project getProject() { project }

        ProjectDetail setProject(Project project) { this.tap { it.project = project } }

        FileDetail path(File file) { path(file.toPath()) }

        FileDetail file(File file) { this.file(file.toPath()) }

        FileDetail path(Path path) { file(path) }

        FileDetail file(Path path) { files.computeIfAbsent(path, p -> new FileDetail(p)) }

        FileDetail getPath(File file) { getPath(file.toPath()) }

        FileDetail getFile(File file) { getFile(file.toPath()) }

        FileDetail getPath(Path path) { getFile(path) }

        FileDetail getFile(Path path) { files.get(path) }

        Set<FileDetail> getAllPaths() { getAllFiles() }

        Set<FileDetail> getAllFiles() { new HashSet<>(files.values()) }

        @Override
        String toString() {
            "ProjectDetail(project=Project(name=${project.name}, path=${project.path}), files=${allFiles})"
        }
    }

    class FileDetail {
        private Path path
        private Map<CoverageMetric, CoverageStat> coverage

        FileDetail() { this(null) }

        FileDetail(Path path) { this.path = path; coverage = [:] }

        FileDetail clearCoverage() { this.tap { coverage.clear() } }

        FileDetail addCoverage(CoverageStat coverage) { this.tap { it.coverage.put(coverage.metric, coverage) } }

        Path getPath() { path }

        FileDetail setPath(Path path) { this.tap { it.@path = path } }

        CoverageStat coverage(CoverageMetric metric) {
            coverage.computeIfAbsent(metric, m -> (m == CoverageMetric.LINE ? LineCoverageStat.from(m) : CoverageStat.from(m)))
        }

        CoverageStat getCoverage(CoverageMetric metric) { coverage.get(metric) }

        Set<CoverageStat> getAllCoverageMetrics() { coverage.values() as Set }

        @Override
        String toString() {
            "FileDetail(path=${path}, coverage=${allCoverageMetrics})"
        }
    }

    class Compliance {
        Map<CoverageMetric, CoverageStat> coverage

        Compliance() { coverage = [:] }

        CoverageStat getLines() { coverage(CoverageMetric.LINE) }

        CoverageStat getBranches() { coverage(CoverageMetric.BRANCH) }

        CoverageStat getInstructions() { coverage(CoverageMetric.INSTRUCTION) }

        CoverageStat getComplexity() { coverage(CoverageMetric.COMPLEXITY) }

        CoverageStat getMethods() { coverage(CoverageMetric.METHOD) }

        CoverageStat getClasses() { coverage(CoverageMetric.CLASS) }

        Compliance clearCoverage() { this.tap { coverage.clear() } }

        Compliance addCoverage(CoverageStat coverage) { this.tap { it.coverage.put(coverage.metric, coverage) } }

        CoverageStat coverage(CoverageMetric metric) {
            coverage.computeIfAbsent(metric, m -> (m == CoverageMetric.LINE ? LineCoverageStat.from(m as CoverageMetric) : CoverageStat.from(m as CoverageMetric)))
        }

        CoverageStat getCoverage(CoverageMetric metric) { coverage.get(metric) }

        Set<CoverageStat> getAllCoverage() { coverage.values() as Set }

        Set<CoverageStat> getFailed() {
            new HashSet<CoverageStat>().tap {
                it.addAll(
                    this.@coverage
                        .values()
                        .findAll {
                            it.didFail
                        })
            }
        }

        Set<CoverageStat> getPassed() {
            new HashSet<CoverageStat>().tap {
                it.addAll(coverage.values().findAll { it.didPass })
            }
        }

        @Override
        String toString() {
            "Compliance(${coverage.values()})"
        }
    }

    class Reports {
        String htmlPath
        String xmlPath
        String csvPath

        @Override
        String toString() {
            "Reports(html=${htmlPath}, xml=${xmlPath}, csv=${csvPath})"
        }
    }
}