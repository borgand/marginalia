package com.github.borgand.marginalia

import com.intellij.openapi.components.Service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Guards against the #1 cause of unclean dynamic plugin reloads: a class registered BOTH
 * via the `@Service` annotation (a light service) AND in plugin.xml as a
 * `<applicationService>`/`<projectService>`. The platform treats that descriptor as
 * invalid/non-dynamic, so the IDE's hot-reload leaves the plugin half-loaded and
 * `getService(...)` returns null at runtime. Every service in this plugin must be
 * registered exactly once — via `@Service` only.
 */
class PluginServiceRegistrationTest : BasePlatformTestCase() {

    fun `test no service is registered both via @Service and in plugin xml`() {
        val xml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        val fqns = Regex("""serviceImplementation="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toList()

        val doubleRegistered = fqns.filter { fqn ->
            runCatching { Class.forName(fqn).isAnnotationPresent(Service::class.java) }.getOrDefault(false)
        }

        assertTrue(
            "These classes are registered in plugin.xml AND annotated @Service " +
                "(remove the plugin.xml entry or the annotation): $doubleRegistered",
            doubleRegistered.isEmpty(),
        )
    }
}
