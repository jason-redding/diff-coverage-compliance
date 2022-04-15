package com.witcraft.diffcoveragecompliance

enum CoveragePercentFormat {
    COMPACT(0, 1),
    NORMAL(1, 1),
    LONG(2, 2)

    int minimumFractionDigits
    int maximumFractionDigits
    int minimumIntegerDigits
    int maximumIntegerDigits

    CoveragePercentFormat(int minimumFractionDigits, int maximumFractionDigits) {
        this(minimumFractionDigits, maximumFractionDigits, 1, 40)
    }

    CoveragePercentFormat(int minimumFractionDigits, int maximumFractionDigits, int minimumIntegerDigits, int maximumIntegerDigits) {
        this.minimumFractionDigits = minimumFractionDigits
        this.maximumFractionDigits = maximumFractionDigits
        this.minimumIntegerDigits = minimumIntegerDigits
        this.maximumIntegerDigits = maximumIntegerDigits
    }
}