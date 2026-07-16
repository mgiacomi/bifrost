package com.lokiscale.bifrost.sample;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SupportedApiUsageArchitectureTest
{
    @Test
    void sampleProductionUsesOnlySupportedBifrostApi()
    {
        noClasses()
                .that().resideInAPackage("com.lokiscale.bifrost.sample..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.lokiscale.bifrost.internal..",
                        "com.lokiscale.bifrost.autoconfigure..")
                .because("sample production code must consume Bifrost only through com.lokiscale.bifrost.api")
                .check(new ClassFileImporter().importPackages("com.lokiscale.bifrost.sample"));
    }
}
