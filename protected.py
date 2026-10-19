"""Packages tvclean refuses to disable or uninstall, no matter what a plan file says.

Everything here keeps a feature the owner uses (inputs, remote, casting, Assistant, updates)
or is Android plumbing whose removal has bootloop / blank-screen reports on TCL sets.
Verified against the package list of a TCL 50C645 (Google TV, Android 11, R51MT05).
"""

PROTECTED = {
    # --- TV hardware: HDMI/AV/tuner inputs and the source menu ---
    "com.tcl.tv",
    "com.tcl.tvinput",
    "com.tcl.suspension",
    "com.tcl.settings",
    "com.tcl.system.server",
    "com.tcl.providers.config",
    "com.tcl.factory.view",
    "com.tcl.framework.custom",
    "com.tcl.android.webview",
    # --- remote control, mic, pairing ---
    "com.tcl.autopair",
    "com.tcl.tcl_bt_rcu_service",
    "com.tcl.micmanager",
    "com.google.android.tv.remote.service",
    # --- casting / mirroring (owner uses them) ---
    "com.google.android.apps.mediashell",   # Chromecast built-in
    "com.tcl.miracast",                     # screen mirroring
    "com.tcl.ttvs",                         # T-Cast phone casting + TCL core/message service
    # --- USB media (owner uses it) ---
    "com.tcl.ui_mediaCenter",
    "com.tcl.gallery",
    # --- system updates ---
    "com.tcl.versionUpdateApp",
    # --- keyboards ---
    "com.tcl.inputmethod.international",
    "com.google.android.inputmethod.latin",
    # --- Google core: Assistant, Play, services ---
    "com.google.android.katniss",
    "com.google.android.tv.assistant",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.android.vending",
    "com.google.android.tts",
    "com.google.android.webview",
    "com.google.android.marvin.talkback",
    "com.google.android.permissioncontroller",
    "com.google.android.packageinstaller",
    "com.google.android.ext.services",
    "com.google.android.ext.shared",
    "com.google.android.modulemetadata",
    "com.google.android.onetimeinitializer",
    # --- home screen: launcherx and setupwraith are NOT protected on purpose. Google TV gives
    # them HOME priority 2 and 1, and Android ignores the preferred home when the top two
    # candidates differ in priority, so a third-party home only works while both sleep.
    "com.android.tv.settings",
    # --- keep by owner's choice ---
    "com.google.android.videos",            # Google TV app
    "com.google.android.apps.tv.dreamx",    # ambient screensaver
    # --- Android plumbing (bootloop / blank screen reports when disabled) ---
    "android",
    "com.android.systemui",
    "com.android.location.fused",
    "com.android.bluetooth",
    "com.android.shell",
    "com.android.providers.settings",
    "com.android.providers.media",
    "com.android.providers.media.module",
    "com.android.providers.tv",
    "com.android.providers.downloads",
    "com.android.externalstorage",
    "com.android.keychain",
    "com.android.certinstaller",
    "com.android.se",
    "com.android.inputdevices",
    "com.android.settings.intelligence",
    "com.android.companiondevicemanager",
    "com.android.captiveportallogin",
    "com.android.networkstack.inprocess",
    "com.android.networkstack.tethering.inprocess",
    "com.android.networkstack.permissionconfig",
    "com.android.dynsystem",
    "com.android.localtransport",
    "com.android.sharedstoragebackup",
    "com.android.wallpaperbackup",
    "com.android.backupconfirm",
    "com.android.statementservice",
    "com.android.proxyhandler",
    "com.android.pacprocessor",
    "com.android.vpndialogs",
    "com.android.hotspot2.osulogin",
    "com.android.soundpicker",
    "com.android.htmlviewer",
    "com.android.dreams.basic",
    "com.android.cts.ctsshim",
    "com.android.cts.priv.ctsshim",
    "com.tvos",
}

# Anything matching these prefixes is treated as protected too (resource overlays, stubs).
PROTECTED_PREFIXES = (
    "com.google.android.overlay.",
    "com.android.tv.settings.",
    "com.android.tethering.overlay",
    "com.android.wifi.resources",
    "com.google.android.tv.frameworkpackagestubs",
    "com.tcl.tclchannel.overlay",  # harmless overlay; disable the app, not the overlay
)

# Streaming apps the owner keeps. Not system-critical, listed so a typo in a plan can't remove them.
KEEP_APPS = {
    "com.netflix.ninja",
    "com.google.android.youtube.tv",
    "com.amazon.amazonvideo.livingroom",
    "com.turkcell.ott",
    "com.disney.disneyplus",
    "com.wbd.stream",
    "com.apple.atve.androidtv.appletv",
    "com.kick.mobile",
    "com.phlox.tvwebbrowser",
}


def is_protected(pkg: str) -> bool:
    return pkg in PROTECTED or pkg in KEEP_APPS or pkg.startswith(PROTECTED_PREFIXES)
