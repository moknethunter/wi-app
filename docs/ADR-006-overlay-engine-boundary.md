# ADR-006 — Overlay engine boundary (V6)

Status: implemented in source; CI and device verification pending.

The single activation app owns the OverlayEngine port. The VPN service obtains the production adapter from Hilt; it no longer constructs EasyTier or serializes its TOML. OverlayRequest and OverlaySnapshot are application data types. EasyTierConfiguration preserves the existing wire configuration; address policy remains transitional until the native acceptance gate is green. EasyTierSnapshot is a temporary source alias for existing policies.

EasyTierEngine isolates synchronous JNI operations behind EasyTierNativeControl. JNI has a process-global registry, so all instances share a process-level lock. This is serialization of native calls, not full lifecycle isolation, and does not make blocking JNI calls safe on the UI thread. A future lifecycle refactor must move blocking start/stop/attach off that thread with ordered descriptor ownership and cancellation.

JNI error messages can contain secrets; adapter exceptions expose only bounded error categories without retaining the original cause. OverlayRequest text is redacted. Tests inject a driver ONLY in JVM tests and retain real Android instrumentation tests. No network protocol, VPN policy, schema, signing identity or production transport is changed by this refactor.

The rootless candidate in commit 82a594a remains under its own CI run 46040859568. This independent boundary work does not declare that test successful and does not enable external connectivity. Do not merge before rootless, DHCP, TUN and relay acceptance; Android Network binding/protect and leak tests remain mandatory. 
