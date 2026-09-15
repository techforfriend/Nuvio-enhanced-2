package com.nuvio.app.features.profiles

internal fun routeProfileSelection(
    profile: NuvioProfile,
    isEditMode: Boolean,
    activeProfileIndex: Int? = null,
    onEditProfile: (NuvioProfile) -> Unit,
    onPinRequired: (NuvioProfile) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
) {
    when {
        isEditMode -> onEditProfile(profile)
        profile.profileIndex == activeProfileIndex -> Unit
        profile.pinEnabled -> onPinRequired(profile)
        else -> onProfileSelected(profile)
    }
}
