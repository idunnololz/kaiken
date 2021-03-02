package com.dropbox.kaiken.processor

import com.dropbox.kaiken.processor.anvil.ClassScanner
import com.dropbox.kaiken.processor.anvil.InjectorGenerator
import com.dropbox.kaiken.processor.anvil.InterfaceGenerator
import com.google.auto.service.AutoService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.extensions.impl.ExtensionPointImpl
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import java.io.File

/**
 * Entry point for the Anvil Kotlin compiler plugin. It registers several callbacks for the
 * various compilation phases.
 */
@AutoService(ComponentRegistrar::class)
class AnvilComponentRegistrar : ComponentRegistrar {
    override fun registerProjectComponents(
            project: MockProject,
            configuration: CompilerConfiguration
    ) {
        val sourceGenFolder = File(configuration.getNotNull(srcGenDirKey))
        val scanner = ClassScanner()

        val codeGenerators = mutableListOf(
                InterfaceGenerator(),
                InjectorGenerator()
        )


        val codeGenerationExtension = CodeGenerationExtension(
                codeGenDir = sourceGenFolder,
                codeGenerators = codeGenerators
        )

        // It's important to register our extension at the first position. The compiler calls each
        // extension one by one. If an extension returns a result, then the compiler won't call any
        // other extension. That usually happens with Kapt in the stub generating task.
        //
        // It's not dangerous for our extension to run first, because we generate code, restart the
        // analysis phase and then don't return a result anymore. That means the next extension can
        // take over. If we wouldn't do this and any other extension won't let our's run, then we
        // couldn't generate any code.
        AnalysisHandlerExtension.registerExtensionFirst(
                project, codeGenerationExtension
        )

        try {
            // This extension depends on Kotlin 1.4.20 and the code fails to compile with older compiler
            // versions. Anvil will only support the new IR backend with 1.4.20. To avoid compilation
            // errors we only add the source code to this module when IR is enabled. So try to
            // dynamically look up the class name and add the extension when it exists.
            val moduleMergerIr = Class.forName("com.squareup.anvil.compiler.ModuleMergerIr")
                    .declaredConstructors
                    .single()
                    .newInstance(scanner) as IrGenerationExtension

            IrGenerationExtension.registerExtension(
                    project, moduleMergerIr
            )
        } catch (ignored: Exception) {
        }
    }

    private fun AnalysisHandlerExtension.Companion.registerExtensionFirst(
            project: MockProject,
            extension: AnalysisHandlerExtension
    ) {
        @Suppress("DEPRECATION")
        val analysisHandlerExtensionPoint = org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions.getArea(project)
                .getExtensionPoint(AnalysisHandlerExtension.extensionPointName)

        val registeredExtensions = AnalysisHandlerExtension.getInstances(project)
        registeredExtensions.forEach {
            // This doesn't work reliably, but that's the best we can do with public APIs. There's a bug
            // for inner classes where they convert the given class to a String "a.b.C.Inner" and then
            // try to remove "a.b.C$Inner". Good times! Workaround is below.
            analysisHandlerExtensionPoint.unregisterExtension(it::class.java)
        }

        if (analysisHandlerExtensionPoint.hasAnyExtensions() &&
                analysisHandlerExtensionPoint is ExtensionPointImpl<AnalysisHandlerExtension>
        ) {
            AnalysisHandlerExtension.getInstances(project)
                    .forEach {
                        analysisHandlerExtensionPoint.unregisterExtensionFixed(it::class.java)
                    }
        }

        check(!analysisHandlerExtensionPoint.hasAnyExtensions()) {
            "There are still registered extensions."
        }

        AnalysisHandlerExtension.registerExtension(project, extension)
        registeredExtensions.forEach { AnalysisHandlerExtension.registerExtension(project, it) }
    }

    private fun <T : AnalysisHandlerExtension> ExtensionPointImpl<T>.unregisterExtensionFixed(
            extensionClass: Class<out T>
    ) {
        // The bug is that they use "extensionClass.canonicalName".
        val classNameToUnregister = extensionClass.name
        unregisterExtensions({ className, _ ->
            classNameToUnregister != className
        }, true)
    }
}

internal const val srcGenDirName = "src-gen-dir"
internal val srcGenDirKey = CompilerConfigurationKey.create<String>("kaiken $srcGenDirName")

internal const val generateDaggerFactoriesName = "generate-dagger-factories"
internal val generateDaggerFactoriesKey =
        CompilerConfigurationKey.create<Boolean>("kaiken $generateDaggerFactoriesName")

