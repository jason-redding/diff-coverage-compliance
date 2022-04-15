package com.witcraft.diffcoveragecompliance

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.options.OptionValues

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Collectors

class DiffCoverageComplianceTask extends DefaultTask {
    public static final String DESCRIPTION = 'Builds coverage report for only modified code, then generates a compliance summary.'
    private static final Pattern PATTERN_GIT_BRANCH_LIST_LINE = ~/^[*]\s+(?:remotes\/)?(?<fullBranch>(origin\/)?(?<branch>\S+))[^\n]*$/

    @Input
    @Optional
    String diffBase

    DiffCoverageComplianceTask() {
        group = 'verification'
        description = DESCRIPTION
    }

    @Option(option = 'diffBase', description = 'Set the branch name against which to generate a diff and compare.')
    void setDiffBase(String diffBase) {
        this.diffBase = diffBase
    }

    @OptionValues('diffBase')
    List<String> getAvailableBranches() {
        DiffCoverageCompliancePlugin.doGitStream(project, 'branch', '-a')
            .map(line -> {
                Matcher m = line =~ PATTERN_GIT_BRANCH_LIST_LINE
                return (m.find() ? m.group('fullBranch') : null)
            })
            .filter(branch -> Objects.nonNull(branch))
            .collect(Collectors.toSet())
            .asList()
            .sort()
    }


    @TaskAction
    void execute() {
        project.ext.diffBase = diffBase
    }
}
