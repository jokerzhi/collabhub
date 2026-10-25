package com.collabhub;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块边界校验（Spring Modulith）。
 *
 * <p>这个测试回答三个问题：
 * <ol>
 *   <li>有没有循环依赖？</li>
 *   <li>有没有模块访问了别人不该暴露的包？</li>
 *   <li>声明的模块结构和实际代码是否一致？</li>
 * </ol>
 *
 * <p>它跑在 {@code mvn verify} 里，边界一旦破了就构建失败 —— 这是「架构不腐化」最便宜的手段。
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(CollabHubApplication.class);

    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }

    @Test
    void discoversExpectedModules() {
        List<String> names = MODULES.stream()
                .map(module -> module.getDisplayName().toLowerCase(Locale.ROOT))
                .toList();

        assertThat(names).contains("platform", "identity", "workspace", "document");
    }
}
