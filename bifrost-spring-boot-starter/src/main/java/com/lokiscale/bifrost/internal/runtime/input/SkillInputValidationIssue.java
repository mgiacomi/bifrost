package com.lokiscale.bifrost.internal.runtime.input;

public record SkillInputValidationIssue(
                String path,
                String code,
                String message,
                Object rejectedValue)
{
}
