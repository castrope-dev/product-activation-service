package com.product.activation;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void domainAndApplicationDoNotDependOnDeliveryOrProviderAdapters() {
        var classes = new ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.product.activation");
        noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
                .resideInAnyPackage("com.product.activation.api..", "com.product.activation.application..",
                        "com.product.activation.infrastructure..", "com.product.activation.exceptions.handler..", "org.springframework..")
                .check(classes);
        noClasses().that().resideInAPackage("..application..").should().dependOnClassesThat()
                .resideInAnyPackage("com.product.activation.api..", "com.product.activation.infrastructure..",
                        "com.product.activation.exceptions.handler..").check(classes);
    }
}
