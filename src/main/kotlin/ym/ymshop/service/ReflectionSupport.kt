package ym.ymshop.service

import java.lang.reflect.Method

internal object ReflectionSupport {

    fun invoke(method: Method, target: Any?, vararg args: Any?): Any? {
        if (!method.canAccess(target)) {
            method.trySetAccessible()
        }
        return method.invoke(target, *args)
    }
}
