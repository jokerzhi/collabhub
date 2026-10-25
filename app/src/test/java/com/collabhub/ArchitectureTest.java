package com.collabhub;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 架构约束（ArchUnit）。
 *
 * <p>和 Spring Modulith 的分工：Modulith 管「模块之间能不能依赖」，
 * ArchUnit 管「代码层面不许出现什么」。两者的规则都会让 CI 失败。
 *
 * <p>规则是<b>可执行的设计文档</b>：以后不管是我改、你改还是 AI 改，
 * 越界了这里就会报错。
 */
@AnalyzeClasses(packages = "com.collabhub", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** 技术底座不许反向依赖业务。 */
    @ArchTest
    static final ArchRule platform_must_not_depend_on_business_modules =
            noClasses()
                    .that().resideInAPackage("com.collabhub.platform..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.collabhub.identity..",
                            "com.collabhub.workspace..",
                            "com.collabhub.document..");

    /** 模块之间的依赖必须是有向无环的。 */
    @ArchTest
    static final ArchRule modules_must_be_free_of_cycles =
            slices()
                    .matching("com.collabhub.(*)..")
                    .should().beFreeOfCycles();

    /** 控制器只负责协议转换，不许直接碰数据库。 */
    @ArchTest
    static final ArchRule controllers_must_not_use_jdbc_directly =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate");
}
