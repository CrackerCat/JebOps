package com.yoavst.jeb.plugins.constarg

import com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexClass
import com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod
import com.pnfsoftware.jeb.core.units.code.java.*
import com.pnfsoftware.jeb.util.logging.GlobalLog
import com.pnfsoftware.jeb.util.logging.ILogger
import com.yoavst.jeb.bridge.UIBridge
import com.yoavst.jeb.utils.*
import com.yoavst.jeb.utils.renaming.RenameEngine
import com.yoavst.jeb.utils.renaming.RenameReason
import com.yoavst.jeb.utils.renaming.RenameRequest

class ConstArgMassRenaming(
    private val renamers: Map<String, ExtendedRenamer>,
    private val isOperatingOnlyOnThisClass: Boolean,
    private var classFilter: Regex
) {
    private val logger: ILogger = GlobalLog.getLogger(javaClass)
    private var effectedMethods: MutableMap<IJavaMethod, IDexClass> = mutableMapOf()

    fun processUnit(unit: IDexUnit, renameEngine: RenameEngine) {
        val decompiler = unit.decompiler
        var seq = getXrefs(unit).asSequence()
        seq = if (isOperatingOnlyOnThisClass) {
            seq.filter { it.classType == UIBridge.currentClass }
        } else {
            seq.filter { classFilter.matches(it.classType.implementingClass) }
        }
        seq.forEach { processMethod(it, unit, decompiler, renameEngine) }
    }

    fun propagate(unit: IDexUnit, renameEngine: RenameEngine) {
        effectedMethods.forEach { (method, cls) ->
            SimpleIdentifierPropagationTraversal(cls, renameEngine).traverse(method.body)
        }

        propagateRenameToGetterAndSetters(unit, renameEngine.stats.effectedClasses, renameEngine)
        unit.refresh()
    }

    fun processMethod(method: IDexMethod, unit: IDexUnit, decompiler: IDexDecompilerUnit, renameEngine: RenameEngine) {
        logger.trace("Processing: ${method.currentSignature}")
        val decompiledMethod = decompiler.decompileDexMethod(method) ?: run {
            logger.warning("Failed to decompile method: ${method.currentSignature}")
            return
        }
        ConstArgRenamingTraversal(
            method,
            decompiledMethod,
            method.classType.implementingClass!!,
            unit,
            renameEngine
        ).traverse(decompiledMethod.body)
    }

    private inner class ConstArgRenamingTraversal(
        private val method: IDexMethod,
        private val javaMethod: IJavaMethod,
        private val cls: IDexClass,
        private val unit: IDexUnit,
        renameEngine: RenameEngine
    ) :
        BasicAstTraversal(renameEngine) {
        override fun traverseNonCompound(statement: IStatement) {
            if (statement is IJavaAssignment) {
                // Don't crash on: "Object x;"
                statement.right?.let { right ->
                    traverseElement(right, statement.left)
                }
            } else {
                traverseElement(statement, null)
            }
        }

        private fun traverseElement(element: IJavaElement, assignee: IJavaLeftExpression? = null): Unit = when {
            element is IJavaCall && element.methodSignature in renamers -> {
                // we found the method we were looking for!
                processMatchedMethod(assignee, renamers[element.methodSignature]!!, element::getRealArgument)
            }
            element is IJavaNew && element.constructor.signature in renamers -> {
                // the method we were looking for was a constructor
                processMatchedMethod(assignee, renamers[element.constructor.signature]!!) { element.arguments[it] }
            }
            else -> {
                // Try sub elements
                element.subElements.forEach { traverseElement(it, assignee) }
            }
        }

        private inline fun processMatchedMethod(assignee: IJavaLeftExpression?, match: ExtendedRenamer, getArg: (Int) -> IJavaElement) {
            val nameArg = getArg(match.constArgIndex)
            if (nameArg is IJavaConstant && nameArg.isString) {
                val result = match(nameArg.string)
                if (!result.className.isNullOrEmpty()) {
                    renameEngine.renameClass(
                        RenameRequest(
                            result.className,
                            RenameReason.MethodStringArgument
                        ), cls
                    )
                }
                if (!result.methodName.isNullOrEmpty()) {
                    renameEngine.renameMethod(
                        RenameRequest(
                            result.methodName,
                            RenameReason.MethodStringArgument
                        ), method, cls
                    )
                }
                if (!result.argumentName.isNullOrEmpty()) {
                    renameElement(getArg(match.renamedArgumentIndex!!), result.argumentName)
                }
                if (!result.assigneeName.isNullOrEmpty() && assignee != null) {
                    renameElement(assignee, result.assigneeName)
                }
            }
        }

        private fun renameElement(element: IJavaElement, name: String) {
            val request = RenameRequest(name, RenameReason.MethodStringArgument)
            when (element) {
                is IJavaDefinition -> renameElement(element.identifier, name)
                is IJavaStaticField -> {
                    val field = element.field ?: run {
                        logger.warning("Failed to get field: $element")
                        return
                    }
                    renameEngine.renameField(request, field, cls)
                }
                is IJavaInstanceField -> {
                    val field = element.field ?: run {
                        logger.warning("Failed to get field: $element")
                        return
                    }
                    renameEngine.renameField(request, field, cls)
                }
                is IJavaIdentifier -> {
                    renameEngine.renameIdentifier(request, element, unit)
                }
                else -> {
                    logger.debug("Unsupported argument type: ${element.elementType}")
                    return
                }
            }
            effectedMethods[javaMethod] = cls
        }
    }

    /**
     * We are going to do very simple "Identifier propagation", to support the case of:
    this.a = anIdentifierIRecovered
     */
    private inner class SimpleIdentifierPropagationTraversal(private val cls: IDexClass, renameEngine: RenameEngine) :
        BasicAstTraversal(renameEngine) {
        override fun traverseNonCompound(statement: IStatement) {
            if (statement is IJavaAssignment) {
                val left = statement.left
                val right = statement.right

                if (right is IJavaIdentifier) {
                    val renameRequest = renameEngine.stats.renamedIdentifiers[right] ?: return
                    if (left is IJavaInstanceField) {
                        val field = left.field ?: run {
                            logger.warning("Failed to get field: $left")
                            return
                        }
                        renameEngine.renameField(
                            RenameRequest(
                                renameRequest.newName,
                                RenameReason.MethodStringArgument
                            ), field, cls
                        )
                    } else if (left is IJavaStaticField) {
                        val field = left.field ?: run {
                            logger.warning("Failed to get field: $left")
                            return
                        }
                        renameEngine.renameField(
                            RenameRequest(
                                renameRequest.newName,
                                RenameReason.MethodStringArgument
                            ), field, cls
                        )
                    }
                }
            }
        }

    }

    private fun getXrefs(unit: IDexUnit): Set<IDexMethod> = renamers.keys.asSequence().mapNotNull(unit::getMethod).onEach {
        logger.info("Found method: ${it.currentSignature}")
    }.mapToPair(unit::xrefsFor).onEach { (method, xrefs) ->
        if (xrefs.isNotEmpty())
            logger.info("Found ${xrefs.size} xrefs for method: ${method.currentSignature}")
    }.flatMap { it.second }.mapTo(mutableSetOf(), unit::getMethod)
}