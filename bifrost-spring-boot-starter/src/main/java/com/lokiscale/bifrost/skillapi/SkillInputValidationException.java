package com.lokiscale.bifrost.skillapi;

import com.lokiscale.bifrost.runtime.input.SkillInputValidationIssue;

import java.util.List;

public class SkillInputValidationException extends SkillException
{
    private final List<SkillInputValidationIssue> issues;

    public SkillInputValidationException(String message, List<SkillInputValidationIssue> issues)
    {
        super(message);
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public List<SkillInputValidationIssue> getIssues()
    {
        return issues;
    }
}
