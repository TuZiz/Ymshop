package ym.ymshop.service

import kotlin.test.Test
import kotlin.test.assertTrue

class ReflectionSupportTest {

    @Test
    fun `invoke can call public method on package-private class`() {
        val clazz = Class.forName("ym.ymshop.testsupport.PackagePrivateTarget")
        val constructor = clazz.getDeclaredConstructor().apply { trySetAccessible() }
        val target = constructor.newInstance()
        val cancelMethod = clazz.getMethod("cancel")
        val stateMethod = clazz.getMethod("isCancelled")

        ReflectionSupport.invoke(cancelMethod, target)

        assertTrue(ReflectionSupport.invoke(stateMethod, target) as Boolean)
    }
}
