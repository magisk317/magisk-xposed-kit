package io.github.magisk317.xposed.keepalive

/**
 * Exact descriptor of a framework method to hook.
 *
 * Matching is on the full descriptor (owner + name + parameter types + return type)
 * rather than the method name alone. Framework targets are heavily overloaded, so a
 * name-only match would bind to an arbitrary overload.
 */
data class MethodShape(
    val owner: String,
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
)

/**
 * One hook target plus the parameter slots the hook reads.
 *
 * [packageIndex] names the argument carrying the package/process name, and
 * [valueIndex] / [secondaryValueIndex] the arguments carrying the values the policy
 * decides on. Keeping the indexes with the descriptor keeps the hook bodies free of
 * magic numbers.
 */
data class IndexedHookTarget(
    val capability: KeepAliveCapability,
    val shape: MethodShape,
    val packageIndex: Int? = null,
    val valueIndex: Int? = null,
    val secondaryValueIndex: Int? = null,
)

/** Outcome of matching a set of [IndexedHookTarget]s against a resolved owner class. */
sealed class MethodResolution<out M : Any> {
    data class Resolved<M : Any>(val target: IndexedHookTarget, val method: M) : MethodResolution<M>()

    /** No descriptor matched: the hook stays uninstalled instead of guessing. */
    data object Missing : MethodResolution<Nothing>()

    /** Several descriptors matched, so the mapping from index to meaning is unknown. */
    data class Ambiguous<M : Any>(val candidates: List<Resolved<M>>) : MethodResolution<Nothing>()
}

/**
 * Matches [targets] against [candidates] by full descriptor.
 *
 * Generic in [M] so the matching rule can live in common code and be tested with a
 * plain fake method type; the Android side passes `java.lang.reflect.Method`.
 */
fun <M : Any> resolveTargets(
    targets: List<IndexedHookTarget>,
    candidates: List<M>,
    describe: (M) -> MethodShape,
): MethodResolution<M> {
    val matches = targets.flatMap { target ->
        candidates
            .filter { candidate -> describe(candidate) == target.shape }
            .map { method -> MethodResolution.Resolved(target, method) }
    }
    return when (matches.size) {
        0 -> MethodResolution.Missing
        1 -> matches.single()
        else -> MethodResolution.Ambiguous(matches)
    }
}
