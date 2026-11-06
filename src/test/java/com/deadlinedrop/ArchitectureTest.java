package com.deadlinedrop;

import com.deadlinedrop.security.PublicEndpoint;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Architectural rules enforcing package layering, constructor injection,
 * and controller endpoint security annotations.
 */
@AnalyzeClasses(packages = "com.deadlinedrop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule apiMustNotDependOnRepository =
            noClasses()
                    .that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule repositoryMustNotDependOnServiceOrApi =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAnyPackage("..service..", "..api..");

    @ArchTest
    static final ArchRule domainMustNotDependOnApi =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..api..");

    @ArchTest
    static final ArchRule noFieldIsAnnotatedAutowired =
            noFields()
                    .should().beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule controllerPublicMethodsMustBeSecuredOrPublic =
            methods()
                    .that().areDeclaredInClassesThat().resideInAPackage("..api..")
                    .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .and().arePublic()
                    .should(new ArchCondition<JavaMethod>("carry @PreAuthorize (method or class) or @PublicEndpoint") {
                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            boolean hasMethodPreAuthorize = method.isAnnotatedWith(PreAuthorize.class);
                            boolean hasClassPreAuthorize = method.getOwner().isAnnotatedWith(PreAuthorize.class);
                            boolean hasPublicEndpoint = method.isAnnotatedWith(PublicEndpoint.class);
                            if (!hasMethodPreAuthorize && !hasClassPreAuthorize && !hasPublicEndpoint) {
                                String message = String.format(
                                        "Public method %s#%s must be annotated with @PreAuthorize or @PublicEndpoint, or its class must have @PreAuthorize",
                                        method.getOwner().getName(), method.getName()
                                );
                                events.add(SimpleConditionEvent.violated(method, message));
                            }
                        }
                    });
}
