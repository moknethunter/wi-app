package com.mokasas.wikall

import com.easytier.jni.EasyTierJNI

/** Private adapter boundary; only this production file invokes the JNI API. */
internal interface EasyTierNativeControl {
    fun parse(configuration: String): Int
    fun start(configuration: String): Int
    fun attach(instance: String, fd: Int): Int
    fun stopAll(): Int
    fun collect(): String?
}

internal object JniEasyTierControl : EasyTierNativeControl {
    override fun parse(configuration: String) = EasyTierJNI.parseConfig(configuration)
    override fun start(configuration: String) = EasyTierJNI.runNetworkInstance(configuration)
    override fun attach(instance: String, fd: Int) = EasyTierJNI.setTunFd(instance, fd)
    override fun stopAll() = EasyTierJNI.retainNetworkInstance(null)
    override fun collect() = EasyTierJNI.collectNetworkInfos()
}
