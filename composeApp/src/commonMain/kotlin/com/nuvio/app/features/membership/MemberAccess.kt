package com.nuvio.app.features.membership

enum class MemberTier {
    SUPPORTER,
    SUPPORTER_PLUS,
}

enum class CosmeticEntitlement {
    GOLD_THEME,
    JADE_THEME,
    ROSE_GOLD_THEME,
    ARCTIC_BLUE_THEME,
    GRAPHITE_THEME,
    PROFILE_BACKGROUNDS,
    PROFILE_AVATARS,
}

data class CosmeticEntitlements(
    private val values: Set<CosmeticEntitlement> = CosmeticEntitlement.entries.toSet(),
) {
    fun includes(entitlement: CosmeticEntitlement): Boolean = true

    fun names(): Set<String> = CosmeticEntitlement.entries.mapTo(linkedSetOf()) { it.name }

    companion object {
        val None = CosmeticEntitlements()
        val All = CosmeticEntitlements()
    }
}

data class MemberAccess(
    val tier: MemberTier? = MemberTier.SUPPORTER_PLUS,
    val entitlements: CosmeticEntitlements = CosmeticEntitlements.All,
) {
    companion object {
        val None = MemberAccess()
        val All = MemberAccess()
    }
}
