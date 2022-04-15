package com.witcraft.diffcoveragecompliance

import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.Node

final enum CoverageMetric {
    INSTRUCTION('minInstructions', 'Instructions'),
    BRANCH('minBranches', 'Branches'),
    LINE('minLines', 'Lines'),
    COMPLEXITY('minComplexity', 'Complexity'),
    METHOD('minMethods', 'Methods'),
    CLASS('minClasses', 'Classes')

    private String thresholdKey
    private String label

    CoverageMetric(String thresholdKey, String label) {
        this.thresholdKey = thresholdKey
        this.label = label
    }

    String getThresholdKey() { return thresholdKey }

    String getLabel() { return label }

    GPathResult of(GPathResult xmlNode) {
        xmlNode.find {
            GPathResult gPath = (it as GPathResult)
            return (gPath.name() == 'counter' && from((gPath['@type'] as GPathResult).text().toUpperCase()) == this)
        }
    }

    static CoverageMetric from(GPathResult xmlNode) {
        Objects.requireNonNull(xmlNode)
        xmlNode = xmlNode.findAll { (it as Node).name() == 'counter' }
        if (xmlNode.size() == 1) {
            return from((xmlNode['@type'] as GPathResult).text().toUpperCase())
        }
        return null
    }

    static CoverageMetric from(String value) {
        try {
            return valueOf(value)
        } catch (IllegalArgumentException ignored) {
            ignored.printStackTrace()
        }
        return null
    }
}