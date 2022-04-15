package com.witcraft.diffcoveragecompliance

import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.Node

import java.text.NumberFormat

class CoverageStat {
    private NumberFormat coverageFormat
    private NumberFormat coverageFormatCompact
    CoverageMetric metric
    ViolationRules rules
    int covered
    int missing
    double thresholdFactor

    protected CoverageStat(CoverageMetric metric, int covered, int missing, ViolationRules rules) {
        this.coverageFormat = NumberFormat.percentInstance.tap {
            maximumFractionDigits = 1
            minimumFractionDigits = 1
        }
        this.coverageFormatCompact = NumberFormat.percentInstance.tap {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }
        this.metric = metric
        this.rules = rules
        this.covered = covered
        this.missing = missing
    }

    static CoverageStat from(GPathResult xmlNode) { from(xmlNode, null) }

    static CoverageStat from(CoverageMetric metric, GPathResult xmlNode, ViolationRules rules) {
        if (xmlNode == null) {
            return null
        }
        xmlNode.findAll { CoverageMetric.from(it as GPathResult) == metric }
            .with {
                from(it, rules)
            }
    }

    static CoverageStat from(GPathResult xmlNode, ViolationRules rules) {
        if (xmlNode == null) {
            return null
        }
        xmlNode
            .findAll { (it as Node).name() == 'counter' }
            .with {
                if (it.size() == 1) {
                    return new CoverageStat(
                        CoverageMetric.from(it),
                        Integer.parseInt((it['@covered'] as GPathResult).text()),
                        Integer.parseInt((it['@missed'] as GPathResult).text()),
                        rules
                    )
                }
                return null
            }
    }

    static CoverageStat from(CoverageMetric metric) { new CoverageStat(metric, 0, 0, null) }

    static CoverageStat from(CoverageMetric metric, int covered) { new CoverageStat(metric, covered, 0, null) }

    static CoverageStat from(CoverageMetric metric, int covered, int missing) {
        new CoverageStat(metric, covered, missing, null)
    }

    static CoverageStat from(CoverageMetric metric, int covered, int missing, double violationThreshold) {
        new CoverageStat(metric, covered, missing, null).tap { it.thresholdFactor = violationThreshold }
    }

    static CoverageStat from(CoverageMetric metric, int covered, int missing, ViolationRules rules) {
        new CoverageStat(metric, covered, missing, rules)
    }

    CoverageStat from(CoverageStat coverage) {
        if (coverage != this) {
            metric = coverage.metric
            rules = coverage.rules
            covered = coverage.covered
            missing = coverage.missing
            thresholdFactor = coverage.thresholdFactor
        }
        return this
    }

    CoverageStat usingRules(ViolationRules rules) { this.tap { it.@rules = rules } }

    CoverageStat withoutRules() { this.tap { it.@rules = null } }

    CoverageStat addCovered(int count) { this.tap { it.@covered += count } }

    CoverageStat addCovered() { addCovered(1) }

    CoverageStat addMissing(int count) { this.tap { it.@missing += count } }

    CoverageStat addMissing() { addMissing(1) }

    int getTotal() { (covered + missing) }

    double getThresholdFactor() {
        if (rules != null && metric != null) {
            return rules.thresholdFor(metric)
        }
        return this.thresholdFactor
    }

    CoverageStat setThresholdFactor(double threshold) {
        this.usingRules(null).tap {
            it.@thresholdFactor = threshold
        }
    }

    double getCoverageFactor() { (total == 0 ? 0 : ((covered as double) / (total as double))) }

    double getMissingFactor() { (total == 0 ? 0 : ((missing as double) / (total as double))) }

    private static String normalizeField(String field) {
        if ("Covered" == field) {
            field = "Coverage"
        }
        return field
    }

    String percent(String field) { getPercent(field) }

    String percent(String field, NumberFormat format) {
        field = normalizeField(field)
        if ("Coverage" == field) {
            getCoveragePercent(format)
        } else if ("Missing" == field) {
            getMissingPercent(format)
        } else {
            null
        }
    }

    String percent(String field, CoveragePercentFormat format) {
        field = normalizeField(field)
        if ("Coverage" == field) {
            getCoveragePercent(format)
        } else if ("Missing" == field) {
            getMissingPercent(format)
        } else {
            null
        }
    }

    String getPercent(String field) {
        field = normalizeField(field)
        if ("Coverage" == field) {
            getCoveragePercent()
        } else if ("Missing" == field) {
            getMissingPercent()
        } else {
            null
        }
    }

    String getPercent(String field, NumberFormat format) {
        field = normalizeField(field)
        double factor
        if ("Coverage" == field) {
            factor = coverageFactor
        } else if ("Missing" == field) {
            factor = missingFactor
        } else {
            factor = 0
        }
        format.format(factor)
    }

    String getPercent(String field, CoveragePercentFormat format) {
        field = normalizeField(field)
        if ("Coverage" == field) {
            getPercentUsing(format, coverageFactor)
        } else if ("Missing" == field) {
            getPercentUsing(format, missingFactor)
        } else {
            null
        }
    }

    String coveragePercent(NumberFormat format) { getCoveragePercent(format) }

    String coveragePercent(CoveragePercentFormat format) { getCoveragePercent(format) }

    String getCoveragePercent() { getCoveragePercent(coverageFormat) }

    String getCoveragePercent(NumberFormat format) { format.format(coverageFactor) }

    String getCoveragePercent(CoveragePercentFormat format) { getPercentUsing(format, coverageFactor) }

    String missingPercent(NumberFormat format) { getMissingPercent(format) }

    String missingPercent(CoveragePercentFormat format) { getMissingPercent(format) }

    String getMissingPercent() { getMissingPercent(coverageFormat) }

    String getMissingPercent(NumberFormat format) { format.format(missingFactor) }

    String getMissingPercent(CoveragePercentFormat format) { getPercentUsing(format, missingFactor) }

    String thresholdPercent(NumberFormat format) { getThresholdPercent(format) }

    String thresholdPercent(CoveragePercentFormat format) { getThresholdPercent(format) }

    String getThresholdPercent() { getThresholdPercent(coverageFormatCompact) }

    String getThresholdPercent(NumberFormat format) { format.format(thresholdFactor) }

    String getThresholdPercent(CoveragePercentFormat format) { getPercentUsing(format, thresholdFactor) }


    private static String getPercentUsing(CoveragePercentFormat format, double factor) {
        NumberFormat percentFormat = NumberFormat.percentInstance
        percentFormat.minimumFractionDigits = format.minimumFractionDigits
        percentFormat.maximumFractionDigits = format.maximumFractionDigits
        percentFormat.minimumIntegerDigits = format.minimumIntegerDigits
        percentFormat.maximumIntegerDigits = format.maximumIntegerDigits
        return percentFormat.format(factor)
    }

    boolean didPass() { (coverageFactor >= thresholdFactor) }

    boolean getDidPass() { didPass() }

    boolean getPassed() { didPass() }

    boolean didFail() { (coverageFactor < thresholdFactor) }

    boolean getDidFail() { didFail() }

    boolean getFailed() { didFail() }

    boolean hasViolationThreshold() { (thresholdFactor > 0) }

    boolean canFail() { hasViolationThreshold() }

    boolean getCanFail() { canFail() }

    @Override String toString() {
        "CoverageStat(metric=${metric}, covered=${covered}, missing=${missing}, coverageFactor=${coverageFactor}, thresholdFactor=${thresholdFactor})"
    }
}