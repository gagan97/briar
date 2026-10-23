package dev.gagan.family.update;

/**
 * Family build configuration, owned by the vendored updater rather than the host app's BuildConfig:
 * the fork then needs no build.gradle edit for its update channel, and a broken sync cannot silently
 * point the app at someone else's server.
 */
public final class FamilyConfig {
    public static final String MANIFEST_URL =
            "https://mumbai-vps-1.tail376c5b.ts.net:8443/org.briarproject.briar.android/manifest";
    public static final String SIGNER_SHA256 = "6a89f50209a93a3a64494b80bc2699d683df95fe9f14dbcb673d36d48f281064";

    private FamilyConfig() {
    }
}
