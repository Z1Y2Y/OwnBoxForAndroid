package io.nekohasekai.sagernet.ui

/** Selects an installed package for a connection without guessing between shared-UID siblings. */
internal object ConnectionPackageResolver {
    private val processAnnotationSuffix = Regex("""\s+\([^)]*\)$""")
    private val packageNamePattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")

    fun matchesPackage(hints: List<String>, packageName: String): Boolean {
        val target = packageName.trim()
        if (target.isEmpty()) return false
        return hints.mapNotNull(::normalizeHint).any { hint ->
            hint == target || hint.startsWith("$target.")
        }
    }

    fun matchesUnambiguousUid(
        connectionUid: Int?,
        applicationUid: Int,
        packageName: String,
        packagesForUid: List<String>,
    ): Boolean {
        if (connectionUid != applicationUid) return false
        val packages = packagesForUid.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        return packages.singleOrNull() == packageName
    }

    fun selectPackage(
        hints: List<String>,
        packagesForUid: List<String>,
        explicitPackageName: String? = null,
        connectionUid: Int? = null,
    ): String? {
        val packages = packagesForUid.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        val explicit = explicitPackageName?.let(::normalizeHint)
            ?.takeIf { packageNamePattern.matches(it) }

        // A package name supplied directly by connection metadata is authoritative. When the
        // UID lookup is temporarily empty, the caller still verifies package existence and UID.
        if (explicit != null && (packages.isEmpty() || explicit in packages)) return explicit
        if (packages.isEmpty()) return null

        val normalizedHints = hints.mapNotNull(::normalizeHint).distinct()
        normalizedHints.forEach { hint ->
            packages.firstOrNull { it == hint }?.let { return it }
        }

        // Process names may be package-prefixed (for example, com.example.app:worker)
        // or a child process whose owning package is the longest package-name prefix.
        normalizedHints.forEach { hint ->
            packages.asSequence()
                .filter { hint.startsWith("$it.") }
                .maxByOrNull(String::length)
                ?.let { return it }
        }

        // NativeInterface maps Android's core/system UIDs to the platform package.
        if (connectionUid != null && connectionUid in 0..1000 && "android" in packages) {
            return "android"
        }

        // A lone package is unambiguous; for shared UIDs, never display an arbitrary sibling's icon.
        return packages.singleOrNull()
    }

    private fun normalizeHint(value: String): String? {
        val withoutUid = value.trim().replace(processAnnotationSuffix, "")
        if (withoutUid.isBlank()) return null
        val processName = withoutUid.substringAfterLast('/').substringBefore(':').trim()
        return processName.takeIf { it.isNotEmpty() }
    }
}
