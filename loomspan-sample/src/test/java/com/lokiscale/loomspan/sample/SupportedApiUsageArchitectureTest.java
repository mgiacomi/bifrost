package com.lokiscale.loomspan.sample;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SupportedApiUsageArchitectureTest
{
    @Test
    void sampleProductionUsesOnlySupportedLoomspanApi()
    {
        noClasses()
                .that().resideInAPackage("com.lokiscale.loomspan.sample..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.lokiscale.loomspan.internal..",
                        "com.lokiscale.loomspan.autoconfigure..")
                .because("sample production code must consume Loomspan only through com.lokiscale.loomspan.api")
                .check(new ClassFileImporter().importPackages("com.lokiscale.loomspan.sample"));
    }
}
