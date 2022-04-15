package com.witcraft.diffcoveragecompliance

import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.Node

import static com.witcraft.diffcoveragecompliance.CoverageMetric.LINE

class LineCoverageStat extends CoverageStat {
    public static final String KEY_COVERED = 'Covered'
    public static final String KEY_MISSING = 'Missing'
    private Map<String, Set<Integer>> lines

    LineCoverageStat(CoverageMetric metric, int covered, int missing, ViolationRules rules) {
        this(covered, missing, rules)
    }

    LineCoverageStat(int covered, int missing, ViolationRules rules, Set<Integer> coveredLines, Set<Integer> missingLines) {
        this(covered, missing, rules)
        addCoveredLines(coveredLines)
        addMissingLines(missingLines)
    }

    LineCoverageStat(int covered, int missing, ViolationRules rules) {
        super(LINE, covered, missing, rules)
        lines = [(KEY_COVERED): new HashSet<Integer>(), (KEY_MISSING): new HashSet<Integer>()]
    }

    static LineCoverageStat from(GPathResult xmlNode) { from(xmlNode, null) }

    static LineCoverageStat from(CoverageMetric metric, GPathResult xmlNode, ViolationRules rules) {
        if (xmlNode == null) {
            return null
        }
        xmlNode.findAll { CoverageMetric.from(it as GPathResult) == metric }
            .with {
                from(it, rules)
            }
    }

    static LineCoverageStat from(GPathResult xmlNode, ViolationRules rules) {
        if (xmlNode == null) {
            return null
        }
        xmlNode.findAll { (it as Node).name() == 'counter' }
            .with {
                if (it.size() == 1) {
                    return new LineCoverageStat(
                        CoverageMetric.from(it),
                        Integer.parseInt((it['@covered'] as GPathResult).text()),
                        Integer.parseInt((it['@missed'] as GPathResult).text()),
                        rules
                    )
                }
                return null
            }
    }

    static LineCoverageStat from(CoverageMetric metric) { new LineCoverageStat(metric, 0, 0, null) }

    static LineCoverageStat from(CoverageMetric metric, int covered) { new LineCoverageStat(metric, covered, 0, null) }

    static LineCoverageStat from(CoverageMetric metric, int covered, int missing) {
        new LineCoverageStat(metric, covered, missing, null)
    }

    static LineCoverageStat from(CoverageMetric metric, int covered, int missing, double violationThreshold) {
        new LineCoverageStat(metric, covered, missing, null).tap { it.thresholdFactor = violationThreshold }
    }

    static LineCoverageStat from(CoverageMetric metric, int covered, int missing, ViolationRules rules) {
        new LineCoverageStat(metric, covered, missing, rules)
    }


    LineCoverageStat from(CoverageStat coverage) {
        super.from(coverage)
        if (coverage instanceof LineCoverageStat) {
            LineCoverageStat lineCoverage = (coverage as LineCoverageStat)
            coveredLines = lineCoverage.coveredLines
            missingLines = lineCoverage.missingLines
        }
        return this
    }

    List<Map.Entry<String, Set<Integer>>> details() { getDetails() }

    List<Map.Entry<String, Set<Integer>>> getDetails() { lines.collect() }

    Set<Integer> getCoveredLines() { lines.computeIfAbsent(KEY_COVERED, k -> new HashSet<>()) }

    Set<Integer> getMissingLines() { lines.computeIfAbsent(KEY_MISSING, k -> new HashSet<>()) }

    //LineCoverageStat clearCoveredLines() { this.tap { it.coveredLines.clear() } }
    LineCoverageStat clearCoveredLines() { this.tap { getCoveredLines().clear() } }

    LineCoverageStat clearMissingLines() { this.tap { getMissingLines().clear() } }

    LineCoverageStat clearAllLines() { clearCoveredLines().clearMissingLines() }

    LineCoverageStat addLines(GPathResult xmlLines) {
        this.tap { xmlLines.findAll { it.name() == 'line' }.each(this::__addLine) }
    }

    LineCoverageStat addLine(GPathResult xmlLine) { __addLine(xmlLine.find { it.name() == 'line' }) }

    private LineCoverageStat __addLine(GPathResult xmlLine) {
        int lineNumber = Integer.parseInt((xmlLine['@nr'] as GPathResult).text())
        int coveredInstructions = Integer.parseInt((xmlLine['@ci'] as GPathResult).text())
        int coveredBranches = Integer.parseInt((xmlLine['@cb'] as GPathResult).text())
        addLine(lineNumber, ((coveredInstructions + coveredBranches) > 0))
    }

    LineCoverageStat addLine(int line, boolean covered) {
        this.tap { (covered ? it.coveredLines.add(line) : it.missingLines.add(line)) }
    }

    LineCoverageStat addCoveredLine(int line) { this.tap { it.coveredLines.add(line) } }

    LineCoverageStat addMissingLine(int line) { this.tap { it.missingLines.add(line) } }

    LineCoverageStat addCoveredLines(Set<Integer> lines) { addLines(lines, true) }

    LineCoverageStat addMissingLines(Set<Integer> lines) { addLines(lines, false) }

    LineCoverageStat addLines(Set<Integer> lines, boolean covered) {
        this.tap {
            if (covered) {
                coveredLines.addAll(lines)
            } else {
                missingLines.addAll(lines)
            }
        }
    }

    LineCoverageStat setCoveredLines(Collection<Integer> lines) {
        this.tap { it.clearCoveredLines().coveredLines.addAll(lines) }
    }

    LineCoverageStat setMissingLines(Collection<Integer> lines) {
        this.tap { it.clearMissingLines().missingLines.addAll(lines) }
    }

    @Override
    String toString() {
        new StringBuilder(super.toString()).tap {
            delete(0, indexOf('('))
            insert(0, 'LineCoverageStat')
            insert(length() - 1, ", coveredLines=${coveredLines.sort()}, missingLines=${missingLines.sort()}")
        }
    }
}