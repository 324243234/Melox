package com.melox.player

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SubcomposeLayoutOwnerTest {
    @Test
    fun aDetachedRootHasNoOutOfFrameExecutor() {
        assertNull(Fixture().executor())
    }

    @Test
    fun anAttachedRootKeepsItsOwnersExecutor() {
        val fixture = Fixture()
        val executor = fixture.attachOwner()
        assertSame(executor, fixture.executor())
    }

    @Test
    fun removingAnOwnerStopsExposingItsExecutor() {
        val fixture = Fixture()
        val executor = fixture.attachOwner()
        assertSame(executor, fixture.executor())
        fixture.detachOwner()
        assertNull(fixture.executor())
    }

    private class Fixture {
        private val rootClass = Class.forName("androidx.compose.ui.node.LayoutNode")
        private val root = instanceWithoutConstructor(rootClass)
        private val ownerClass = Class.forName("androidx.compose.ui.node.Owner")
        private val ownerField = rootClass.getDeclaredField("owner").apply { isAccessible = true }
        private val stateClass = Class.forName("androidx.compose.ui.layout.LayoutNodeSubcompositionsState")
        private val state = instanceWithoutConstructor(stateClass).also {
            stateClass.getDeclaredField("root").apply { isAccessible = true }.set(it, root)
        }
        private val executorGetter = stateClass.getDeclaredMethod("getOutOfFrameExecutor").apply {
            isAccessible = true
        }

        fun attachOwner(): Any {
            val executorClass = Class.forName("androidx.compose.ui.node.OutOfFrameExecutor")
            val executor = Proxy.newProxyInstance(executorClass.classLoader, arrayOf(executorClass)) { _, _, _ -> null }
            val owner = Proxy.newProxyInstance(ownerClass.classLoader, arrayOf(ownerClass)) { _, method, _ ->
                if (method.name == "getOutOfFrameExecutor") executor else null
            }
            ownerField.set(root, owner)
            return executor
        }

        fun detachOwner() {
            ownerField.set(root, null)
        }

        fun executor(): Any? = try {
            executorGetter.invoke(state)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }

        private fun instanceWithoutConstructor(type: Class<*>): Any {
            // The getter needs only the owner field; drawing constructors require Android.
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return checkNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
        }
    }
}
