package com.easytier.jni

/** Native exports verified against EasyTier v2.6.4. */
internal object EasyTierJNI {
    init {
        System.loadLibrary("easytier_ffi")
        System.loadLibrary("easytier_android_jni")
    }

    @JvmStatic external fun parseConfig(config: String): Int
    @JvmStatic external fun runNetworkInstance(config: String): Int
    @JvmStatic external fun setTunFd(instanceName: String, fd: Int): Int
    @JvmStatic external fun retainNetworkInstance(instanceNames: Array<String>?): Int
    @JvmStatic external fun collectNetworkInfos(): String?
    @JvmStatic external fun getLastError(): String?
}
